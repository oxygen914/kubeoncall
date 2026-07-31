#!/usr/bin/env bash
set -euo pipefail

check_max_lines() {
  local file="$1"
  local maximum="$2"
  local actual
  actual="$(wc -l < "${file}")"
  if ((actual > maximum)); then
    printf '%s has %d lines; responsibility budget is %d\n' "${file}" "${actual}" "${maximum}" >&2
    return 1
  fi
}

# These were the largest cross-language hotspots split during the style convergence pass. The
# budgets prevent them from silently absorbing the extracted responsibilities again.
check_max_lines frontend/src/features/operations/OperationsPage.tsx 250
check_max_lines sandbox-controller/internal/httpapi/server.go 300
check_max_lines backend/src/main/java/com/kubeoncall/sandbox/SandboxRunRepository.java 600
