#!/usr/bin/env bash
# Esper host bootstrap (docs/SERVER.md §9): pinned k3s and cert-manager, the
# `esper` namespace and its Secrets. Idempotent; never writes a secret to git.
#   cd deploy && sudo ./host-setup.sh [--rp-origins 'android:apk-key-hash:...']
# shortcut: no firewall yet (#12). 80/443 are open to everyone, not only
# Cloudflare, and 6443/10250 answer publicly (both need cluster credentials).
# Add the Cloudflare-only nftables prerouting rules before the beta is public.
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo "run with sudo" >&2; exit 1; }

K3S_VERSION="v1.37.2+k3s1"
CERT_MANAGER_VERSION="v1.21.3"

RP_ORIGINS=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --rp-origins) RP_ORIGINS="${2:?--rp-origins needs a value}"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

PUBLIC_IP="$(ip -4 route get 1.1.1.1 | awk '{for (i = 1; i < NF; i++) if ($i == "src") print $(i + 1)}')"
[[ -n "$PUBLIC_IP" ]] || { echo "cannot find the public IPv4 address" >&2; exit 1; }
TS_NAME="$(tailscale status --json 2>/dev/null |
  python3 -c 'import json, sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))' 2>/dev/null || true)"
TS_IP="$(tailscale ip -4 2>/dev/null || true)"
[[ -n "$TS_NAME" ]] || echo "warning: Tailscale is not up; CI cannot reach the cluster API" >&2

# --- k3s -------------------------------------------------------------------
install -d /etc/rancher/k3s /var/lib/rancher/k3s/server/manifests
new_config="$(mktemp)"
{
  echo 'write-kubeconfig-mode: "0600"'
  # One node: host-gw routes pods without a VXLAN port open to the internet.
  echo 'flannel-backend: host-gw'
  # ServiceLB DNATs 80/443 on every local address, the Tailscale one included,
  # which would hijack `tailscale serve`. Traefik takes only the public IP (below).
  echo 'disable: [servicelb]'
  echo 'tls-san:'
  for san in "$TS_NAME" "$TS_IP"; do [[ -n "$san" ]] && echo "  - $san"; done
} > "$new_config"
config_changed=false
if ! cmp -s "$new_config" /etc/rancher/k3s/config.yaml; then
  install -m 600 "$new_config" /etc/rancher/k3s/config.yaml
  config_changed=true
fi
rm -f "$new_config"

cat > /var/lib/rancher/k3s/server/manifests/traefik-config.yaml <<EOF
apiVersion: helm.cattle.io/v1
kind: HelmChartConfig
metadata:
  name: traefik
  namespace: kube-system
spec:
  valuesContent: |-
    service:
      spec:
        type: ClusterIP
        externalIPs: [${PUBLIC_IP}]
EOF

if [[ "$(k3s --version 2>/dev/null | head -1)" != *"$K3S_VERSION"* ]] || $config_changed; then
  curl -sfL https://get.k3s.io | INSTALL_K3S_VERSION="$K3S_VERSION" sh -s - server
fi

kubectl() { k3s kubectl "$@"; }
for _ in $(seq 60); do kubectl get nodes -o name 2>/dev/null | grep -q . && break; sleep 3; done
kubectl wait --for=condition=Ready node --all --timeout=180s

# --- cert-manager ----------------------------------------------------------
kubectl apply -f "https://github.com/cert-manager/cert-manager/releases/download/${CERT_MANAGER_VERSION}/cert-manager.yaml"
for d in cert-manager cert-manager-cainjector cert-manager-webhook; do
  kubectl -n cert-manager rollout status "deploy/$d" --timeout=180s
done

# --- namespace and Secrets -------------------------------------------------
kubectl create namespace esper --dry-run=client -o yaml | kubectl apply -f -

secret_value() { # namespace name key
  kubectl -n "$1" get secret "$2" -o "jsonpath={.data.$3}" 2>/dev/null | base64 -d
}

# Created once: re-running never rotates the database password.
PG_PASSWORD="$(secret_value esper postgres password || true)"
if [[ -z "$PG_PASSWORD" ]]; then
  PG_PASSWORD="$(openssl rand -hex 24)"
  kubectl -n esper create secret generic postgres --from-file=password=<(printf %s "$PG_PASSWORD")
fi

[[ -n "$RP_ORIGINS" ]] || RP_ORIGINS="$(secret_value esper esper-server ESPER_RP_ORIGINS || true)"
if [[ -z "$RP_ORIGINS" && -t 0 ]]; then
  read -rp "Passkey origin (android:apk-key-hash:..., Enter to set later): " RP_ORIGINS
fi
# Values go through a file descriptor, not argv, so `ps` never shows them.
kubectl -n esper create secret generic esper-server --dry-run=client -o yaml \
  --from-env-file=<(printf 'ESPER_DB_URL=postgres://esper:%s@postgres:5432/esper\nESPER_RP_ORIGINS=%s\n' \
    "$PG_PASSWORD" "$RP_ORIGINS") | kubectl apply -f -
[[ -n "$RP_ORIGINS" ]] || echo "warning: no passkey origin; the server will not start until you re-run with --rp-origins" >&2

if [[ -z "$(secret_value cert-manager cloudflare-api-token api-token || true)" ]]; then
  CF_TOKEN=""
  [[ -t 0 ]] && read -rsp "Cloudflare API token (Zone:DNS:Edit on esper.media, Enter to skip): " CF_TOKEN && echo
  if [[ -n "$CF_TOKEN" ]]; then
    kubectl -n cert-manager create secret generic cloudflare-api-token --from-file=api-token=<(printf %s "$CF_TOKEN")
  else
    echo "warning: no Cloudflare token; the esper.media certificate will not be issued until you re-run" >&2
  fi
fi

echo
echo "Done. For the GitHub secret ESPER_KUBECONFIG:"
echo "  sudo k3s kubectl config view --raw | sed 's#https://127.0.0.1:6443#https://${TS_NAME:-HOST}:6443#'"
