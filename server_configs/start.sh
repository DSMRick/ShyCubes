#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${DIR}"

JAVA_CMD=(java -Xms10G -Xmx10G -jar server.jar nogui)

stop_existing_servers() {
  local parent_pid="${PPID:-0}"
  local pids=()
  local pid

  # Find running java processes executing server.jar
  while IFS= read -r pid; do
    [[ -z "${pid}" ]] && continue
    # When invoked by Paper's in-game /restart shutdown hook, PPID is the JVM
    # that is already shutting itself down — don't send a redundant signal to it.
    if [[ ! -t 0 && "${pid}" == "${parent_pid}" ]]; then
      continue
    fi
    pids+=("${pid}")
  done < <(pgrep -f "java.*server\.jar" || true)

  if (( ${#pids[@]} > 0 )); then
    echo "Stopping running Minecraft server(s): ${pids[*]}..."
    kill -TERM "${pids[@]}" 2>/dev/null || true

    # Wait up to 30 seconds for clean world save & shutdown
    local waited=0
    while (( waited < 30 )); do
      local alive=false
      for pid in "${pids[@]}"; do
        if kill -0 "${pid}" 2>/dev/null; then
          alive=true
          break
        fi
      done
      if [[ "${alive}" == "false" ]]; then
        break
      fi
      sleep 1
      (( waited++ ))
    done

    # Force kill any stragglers that refused to exit
    for pid in "${pids[@]}"; do
      if kill -0 "${pid}" 2>/dev/null; then
        echo "Force killing unresponsive server PID ${pid}..."
        kill -9 "${pid}" 2>/dev/null || true
      fi
    done
  fi

  # If triggered by Paper's /restart hook (where parent_pid is a java process), wait for parent JVM to exit
  if [[ ! -t 0 && "${parent_pid}" -gt 1 ]] && ps -p "${parent_pid}" -o comm= 2>/dev/null | grep -q "java"; then
    local waited=0
    while kill -0 "${parent_pid}" 2>/dev/null && (( waited < 30 )); do
      sleep 1
      (( waited++ ))
    done
  fi
}

if [[ "${1:-}" == "--wait" ]]; then
  # Synchronous background restart (used by CI/CD or scripts):
  # stop existing server(s), launch detached via setsid/nohup, and verify startup.
  mkdir -p logs
  stop_existing_servers
  if command -v setsid >/dev/null 2>&1; then
    setsid nohup "${JAVA_CMD[@]}" </dev/null >> logs/restart.out 2>&1 &
  else
    nohup "${JAVA_CMD[@]}" </dev/null >> logs/restart.out 2>&1 &
  fi
  sleep 2
elif [ -t 0 ]; then
  # Interactive terminal invocation: stop any running server, then start in foreground
  stop_existing_servers
  exec "${JAVA_CMD[@]}"
else
  # Invoked by Paper/Spigot /restart shutdown hook (no TTY attached):
  # detach immediately so the stopping JVM can finish exiting, then start.
  mkdir -p logs
  (
    stop_existing_servers
    if command -v setsid >/dev/null 2>&1; then
      setsid nohup "${JAVA_CMD[@]}" </dev/null >> logs/restart.out 2>&1 &
    else
      nohup "${JAVA_CMD[@]}" </dev/null >> logs/restart.out 2>&1 &
    fi
  ) >/dev/null 2>&1 &
fi
