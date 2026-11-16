# Esper runs on single-node k3s on our own server, not a systemd service on an Oracle VM

The server runs on a single-node k3s cluster on Levi's own server, behind Cloudflare at `esper.media`. It is deployed as plain Kubernetes manifests (Kustomize) that use no provider-specific service, so the same manifests run on any conformant cluster, and adding a service means adding manifests. This replaces the beta plan of one systemd unit plus Caddy on an Oracle Always Free VM, which tied the deploy to one machine's apt packages and to a free tier that reclaims idle instances.

## Considered Options

- **Oracle Always Free VM, systemd + Caddy** (the beta plan in `docs/SERVER.md` before this ADR): rejected. Every new service is another hand-installed unit, and the deploy only works on that machine.
- **Full upstream Kubernetes (kubeadm) or MicroK8s**: rejected. k3s is conformant, so the manifests stay portable, and it fits a 2 vCPU / 8 GB host.

## Consequences

- Postgres runs inside the cluster as `postgres:18`. Major versions are upgraded deliberately (dump and restore), never by a floating tag. Nightly `pg_dump` backups stay on the same host, so losing the host loses them: off-site copies are still owed.
- The cluster API and kubelet are reachable only over Tailscale. CI deploys by joining the tailnet. Public 80/443 accept only Cloudflare's IP ranges, so the rate limit must read the client IP from `CF-Connecting-IP`.
- Cloudflare is the only provider-specific part, kept to the cert-manager DNS-01 issuer and the host firewall.
- The passkey RP ID becomes `esper.media`, and `assetlinks.json` is served there. Passkeys are bound to the RP ID forever, so this was fixed before any beta tester registered; changing it later makes every player register again.
