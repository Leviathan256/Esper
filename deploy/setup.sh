#!/usr/bin/env bash
# One-time (re-runnable) bootstrap for the Esper server on Oracle Always Free
# Ubuntu 24.04 (arm64). Run from the repo's deploy/ dir:
#   sudo ./setup.sh <host-name> <deploy-public-key-file>
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo "run with sudo" >&2; exit 1; }
HOST="${1:?usage: setup.sh <host-name> <deploy-public-key-file>}"
PUBKEY="${2:?usage: setup.sh <host-name> <deploy-public-key-file>}"
[[ -f "$PUBKEY" ]] || { echo "no such file: $PUBKEY" >&2; exit 1; }
cd "$(dirname "$0")"

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y openjdk-17-jre-headless postgresql caddy rsync sudo netfilter-persistent

id esper >/dev/null 2>&1 || useradd --system --home /opt/esper --shell /bin/bash esper
install -d -o esper -g esper /opt/esper /opt/esper/server
install -d -m 700 -o esper -g esper /opt/esper/.ssh
install -m 600 -o esper -g esper "$PUBKEY" /opt/esper/.ssh/authorized_keys
install -d /etc/esper

# Database + env file: only created once, so re-running never rotates the password.
if [[ ! -f /etc/esper/server.env ]]; then
  PW="$(openssl rand -hex 24)"
  sudo -u postgres psql -v ON_ERROR_STOP=1 -c "CREATE ROLE esper LOGIN PASSWORD '$PW'" \
    -c "CREATE DATABASE esper OWNER esper"
  install -m 600 -o esper -g esper server.env.example /etc/esper/server.env
  sed -i "s/^ESPER_DB_PASSWORD=.*/ESPER_DB_PASSWORD=$PW/" /etc/esper/server.env
fi

# Oracle's Ubuntu image rejects everything but SSH in iptables (the VCN list is separate).
for p in 80 443; do
  iptables -C INPUT -p tcp --dport $p -j ACCEPT 2>/dev/null ||
    iptables -I INPUT 1 -p tcp --dport $p -m state --state NEW -j ACCEPT
done
netfilter-persistent save

install -m 644 esper-server.service /etc/systemd/system/esper-server.service
install -m 644 Caddyfile /etc/caddy/Caddyfile
install -d /etc/systemd/system/caddy.service.d
printf '[Service]\nEnvironment=ESPER_HOST=%s\n' "$HOST" >/etc/systemd/system/caddy.service.d/esper.conf
echo 'esper ALL=(root) NOPASSWD: /usr/bin/systemctl restart esper-server' >/etc/sudoers.d/esper
chmod 440 /etc/sudoers.d/esper && visudo -cf /etc/sudoers.d/esper

systemctl daemon-reload
# esper-server is enabled but only starts once the first deploy has put bin/server in place.
systemctl enable esper-server
systemctl enable caddy && systemctl restart caddy
echo "Done. Next: set ESPER_RP_ORIGINS in /etc/esper/server.env, then deploy (see docs/BETA_SETUP_LEVI.md)."
