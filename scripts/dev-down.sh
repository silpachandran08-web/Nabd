#!/usr/bin/env bash
# Stops the Nabd dev backend (8080) and frontend (3000/3001) if running.
set -uo pipefail

for port in 8080 3000 3001; do
  pids="$(lsof -ti :"$port" 2>/dev/null || true)"
  if [ -n "$pids" ]; then
    echo "Stopping process on :$port (pid $pids)"
    kill $pids
  fi
done
