#!/usr/bin/env bash
# TD-FUT-013 / ADR-0018: one-time setup for a fresh production VM — the "manual, rarely-repeated,
# not yet worth Terraform" step ADR-0018's own Decision 3 names explicitly. Run once, as root (or
# via sudo), on a fresh Ubuntu/Debian host with a public IP and an already-resolving DNS record
# (deployment-runbook.md §2's own prerequisites).
#
# Idempotent — safe to re-run (e.g. after a host reboot mid-setup, or to pick up a Docker update):
# every step below checks the current state before changing it, never blindly overwrites.
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/RedeArmy/Clavaris/master/scripts/host/bootstrap.sh | sudo bash
# or, after a manual download:
#   sudo ./bootstrap.sh [git-ref]     # git-ref defaults to "master"

set -euo pipefail

GIT_REF="${1:-master}"
REPO_RAW_BASE="https://raw.githubusercontent.com/RedeArmy/Clavaris/${GIT_REF}"
DEPLOY_USER="clavaris"
DEPLOY_DIR="/opt/clavaris"

log() {
  printf '\n\033[1;32m==>\033[0m %s\n' "$1"
}

if [ "$(id -u)" -ne 0 ]; then
  echo "This script must run as root (or via sudo) — it installs Docker and creates a system user." >&2
  exit 1
fi

log "Installing Docker Engine + Compose plugin (if not already present)"
if ! command -v docker >/dev/null 2>&1; then
  # Official convenience script — same "trust a named, well-vetted install path over a hand-rolled
  # one" reasoning this project already applies elsewhere (ADR-0001), not something worth
  # reimplementing per-distro apt/dnf logic for.
  curl -fsSL https://get.docker.com | sh
else
  echo "Docker already installed ($(docker --version)) — skipping."
fi

log "Creating the '${DEPLOY_USER}' deploy user (if not already present)"
if ! id "${DEPLOY_USER}" >/dev/null 2>&1; then
  useradd --system --create-home --shell /bin/bash "${DEPLOY_USER}"
  usermod -aG docker "${DEPLOY_USER}"
else
  echo "User '${DEPLOY_USER}' already exists — skipping creation."
  usermod -aG docker "${DEPLOY_USER}" # idempotent even if already a member
fi

log "Setting up ${DEPLOY_DIR}"
mkdir -p "${DEPLOY_DIR}"
cd "${DEPLOY_DIR}"

# Deliberately NOT a full `git clone` of the whole monorepo — the production host only ever needs
# to run these three files (the app's own source is irrelevant here; ci.yml already built and
# pushed the real image). Faster to fetch, smaller attack surface (no build tooling, no source
# code, nothing to accidentally `mvn package` on a production host), and matches "easy and fast"
# directly.
log "Fetching the deployment files (ref: ${GIT_REF})"
for f in docker-compose.prod.yml Caddyfile .env.example; do
  curl -fsSL "${REPO_RAW_BASE}/${f}" -o "${f}.new"
  mv "${f}.new" "${f}"
done

if [ ! -f .env ]; then
  log "No .env found — creating one from .env.example. FILL IN EVERY VALUE before deploying."
  cp .env.example .env
else
  echo ".env already exists — leaving it untouched (never overwritten by this script)."
fi

log "Locking down .env permissions"
chown "${DEPLOY_USER}:${DEPLOY_USER}" .env docker-compose.prod.yml Caddyfile .env.example
chmod 600 .env
chmod 644 docker-compose.prod.yml Caddyfile .env.example

log "Done."
cat <<EOF

Next steps (see docs/05-engineering/deployment-runbook.md for the full walkthrough):
  1. Confirm this VM's public IP has a DNS A record for the domain you'll set as CLAVARIS_DOMAIN.
  2. Open ports 80 and 443 in this host's firewall/security group if not already open.
  3. Fill in every value in ${DEPLOY_DIR}/.env — every entry docker-compose.prod.yml requires
     fails loudly at "up" time if left blank, by design.
  4. As the '${DEPLOY_USER}' user:  cd ${DEPLOY_DIR} && ./deploy.sh
     (fetch deploy.sh the same way this script was fetched, from scripts/host/deploy.sh)
EOF
