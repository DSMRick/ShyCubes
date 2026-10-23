#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${REPO_ROOT}/.deploy.env"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Error: ${ENV_FILE} not found."
  echo "Copy .deploy.env.example to .deploy.env and configure your server connection."
  exit 1
fi

# Load private deployment variables
# shellcheck disable=SC1090
source "${ENV_FILE}"

DEPLOY_SERVER_ROOT="${DEPLOY_SERVER_ROOT:-/home/opc/mc}"
DEPLOY_REMOTE_DIR="${DEPLOY_REMOTE_DIR:-${DEPLOY_SERVER_ROOT}/plugins}"
BACKUP_ROOT="${REPO_ROOT}/${LOCAL_BACKUP_DIR:-server_data}"
CONFIGS_ROOT="${REPO_ROOT}/server_configs"

SSH_OPTS="-i ${DEPLOY_SSH_KEY} -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new"
REMOTE_CONN="${DEPLOY_USER}@${DEPLOY_HOST}"

