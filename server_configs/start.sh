#!/usr/bin/env bash
set -euo pipefail

SERVICE_NAME="shycubes.service"

# When Paper's /restart runs inside shycubes.service, the JVM is already shutting down
# and systemd's Restart=always (RestartSec=3) will automatically start the new server
# as soon as the JVM exits. Returning 0 immediately prevents hanging the shutdown hook!
if [[ ! -t 0 ]] && [[ "${INVOCATION_ID:-}" != "" || "${PPID:-0}" != "1" ]]; then
  if ps -p "${PPID:-0}" -o comm= 2>/dev/null | grep -q "java"; then
    exit 0
  fi
fi

# Otherwise (called from CLI ./start.sh or GitHub Actions), delegate to systemd:
exec sudo systemctl restart "${SERVICE_NAME}"
