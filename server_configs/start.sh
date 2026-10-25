#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${DIR}"

JAVA_CMD=(java -Xms10G -Xmx10G -jar server.jar nogui)

if [ -t 0 ]; then
  # Interactive terminal invocation
  exec "${JAVA_CMD[@]}"
else
  # Invoked by Paper/Spigot /restart shutdown hook (no TTY attached).
  # Detach and wait briefly for the stopping JVM to release ports and world/session.lock.
  mkdir -p logs
  (
    sleep 2
    nohup "${JAVA_CMD[@]}" </dev/null >> logs/restart.out 2>&1 &
  ) >/dev/null 2>&1 &
fi
