#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/.deploy.env"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Error: ${ENV_FILE} not found."
  echo "Copy .deploy.env.example to .deploy.env and configure your server connection."
  exit 1
fi

# Load private deployment variables
# shellcheck disable=SC1090
source "${ENV_FILE}"

# Allow passing a specific file path, or auto-detect the built plugin jar
SOURCE_FILE="${1:-}"

if [[ -z "${SOURCE_FILE}" ]]; then
  # Look in standard Gradle (build/libs) or Maven (target) output directories
  if compgen -G "${SCRIPT_DIR}/build/libs/*.jar" > /dev/null; then
    SOURCE_FILE="$(ls -t "${SCRIPT_DIR}"/build/libs/*.jar | grep -v -E '(-sources|-javadoc|-dev)\.jar$' | head -n 1)"
  elif compgen -G "${SCRIPT_DIR}/target/*.jar" > /dev/null; then
    SOURCE_FILE="$(ls -t "${SCRIPT_DIR}"/target/*.jar | grep -v -E '(original-|-sources|-javadoc|-shaded)\.jar$' | head -n 1)"
  fi
fi

if [[ -z "${SOURCE_FILE}" || ! -f "${SOURCE_FILE}" ]]; then
  echo "Error: No JAR file found to deploy."
  echo "Usage: ./deploy.sh [path/to/file.jar]"
  exit 1
fi

DEST_NAME="${PLUGIN_JAR_NAME:-$(basename "${SOURCE_FILE}")}"
REMOTE_TARGET="${DEPLOY_USER}@${DEPLOY_HOST}:${DEPLOY_REMOTE_DIR}/${DEST_NAME}"

echo "Deploying $(basename "${SOURCE_FILE}") -> ${REMOTE_TARGET} ..."
scp -i "${DEPLOY_SSH_KEY}" -o IdentitiesOnly=yes "${SOURCE_FILE}" "${REMOTE_TARGET}"
echo "Deployment complete!"

