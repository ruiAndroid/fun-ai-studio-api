#!/usr/bin/env bash
set -euo pipefail

# Export Podman container metrics via node_exporter textfile collector.
#
# Requirements:
# - node_exporter started with:
#   --collector.textfile.directory=/var/lib/node_exporter/textfile_collector
# - podman available and rootful containers (or adjust for rootless socket)
#
# Output metrics:
# - podman_container_info (gauge=1)
# - podman_container_size_rw_bytes / podman_container_size_rootfs_bytes (gauge)
# - podman_container_start_time_seconds (gauge, unix epoch seconds)
# - podman_container_cpu_percent (gauge, snapshot from `podman stats`)
# - podman_container_mem_usage_bytes / podman_container_mem_limit_bytes (gauge)
# - podman_container_net_rx_bytes_total / podman_container_net_tx_bytes_total (counter-like, total since container start per `podman stats`)
# - podman_container_block_read_bytes_total / podman_container_block_write_bytes_total (counter-like, total since container start per `podman stats`)
#
# Notes:
# - This script only exports RUNNING containers (podman ps -q)
# - You can restrict containers by name via PODMAN_METRICS_NAME_REGEX.

OUT_DIR="${OUT_DIR:-/var/lib/node_exporter/textfile_collector}"
OUT_FILE="${OUT_FILE:-${OUT_DIR}/podman_containers.prom}"
TMP="$(mktemp)"

# runtime: rt-u-<userId>-<appId> + funai-traefik
# workspace: ws-u-<userId> + verdaccio
PODMAN_METRICS_NAME_REGEX="${PODMAN_METRICS_NAME_REGEX:-^(ws-u-[0-9]+|rt-u-[0-9]+-[0-9]+|verdaccio|funai-traefik)$}"

esc() {
  local s="${1:-}"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\n'/ }"
  printf '%s' "$s"
}

to_bytes() {
  # Convert "12.3kB", "12.3KiB", "1MB", "1MiB", "1024" -> bytes (integer).
  # Unknown formats -> 0.
  local raw="${1:-}"
  raw="${raw// /}"
  [[ -z "$raw" || "$raw" == "0" || "$raw" == "0B" ]] && { echo 0; return; }
  [[ "$raw" =~ ^([0-9]+)([A-Za-z]+)?$ ]] || [[ "$raw" =~ ^([0-9]+(\.[0-9]+)?)([A-Za-z]+)$ ]] || { echo 0; return; }

  local num unit
  if [[ "$raw" =~ ^([0-9]+(\.[0-9]+)?)([A-Za-z]+)?$ ]]; then
    num="${BASH_REMATCH[1]}"
    unit="${BASH_REMATCH[3]:-B}"
  else
    echo 0
    return
  fi

  # Normalize unit
  unit="${unit^^}"

  # Decimal (kB/MB/GB/TB) and binary (KiB/MiB/GiB/TiB)
  local mul=1
  case "$unit" in
    B) mul=1 ;;
    KB) mul=1000 ;;
    MB) mul=1000000 ;;
    GB) mul=1000000000 ;;
    TB) mul=1000000000000 ;;
    KIB) mul=1024 ;;
    MIB) mul=$((1024**2)) ;;
    GIB) mul=$((1024**3)) ;;
    TIB) mul=$((1024**4)) ;;
    *) mul=1 ;;
  esac

  # Use awk for float * int
  awk -v n="$num" -v m="$mul" 'BEGIN { printf "%.0f", (n*m) }'
}

split_io_pair_to_bytes() {
  # Input: "12.3MB / 4.5kB" -> echo "rxBytes txBytes"
  local raw="${1:-}"
  raw="${raw// /}"
  local left right
  left="${raw%%/*}"
  right="${raw#*/}"
  echo "$(to_bytes "$left") $(to_bytes "$right")"
}

to_epoch_seconds() {
  # Convert RFC3339 timestamp (podman StartedAt) to unix epoch seconds.
  # Common formats:
  # - 2026-01-26T01:02:03.123456789Z
  # - 2026-01-26 18:04:34.694990939 +0800 CST
  local ts="${1:-}"
  [[ -z "$ts" || "$ts" == "0001-01-01T00:00:00Z" ]] && { echo 0; return; }

  # Strip fractional seconds (date doesn't like nanoseconds on some distros).
  # Keep timezone suffix (Z / +08:00 / +0800).
  local ts2
  # Use basic regex for portability (avoid sed -r/-E differences).
  ts2="$(echo "$ts" | sed 's/\.[0-9][0-9]*//')"
  # If podman includes a trailing timezone name (e.g. "CST"), drop it to help parsers.
  ts2="$(echo "$ts2" | sed 's/ [A-Za-z][A-Za-z][A-Za-z][A-Za-z]*$//')"

  # GNU date parses RFC3339 with timezone. If parsing fails, return 0.
  local out
  out="$(date -d "$ts2" +%s 2>/dev/null || true)"
  [[ -n "$out" && "$out" =~ ^[0-9]+$ ]] && { echo "$out"; return; }

  # Fallback: try python3 if available (handles more ISO8601 variants).
  if command -v python3 >/dev/null 2>&1; then
    python3 - <<'PY' "$ts2" 2>/dev/null || echo 0
import sys, datetime
s = sys.argv[1].strip()
if not s:
    print(0); raise SystemExit
# Try ISO8601 first
try:
    ss = s
    if ss.endswith('Z'):
        ss = ss[:-1] + '+00:00'
    dt = datetime.datetime.fromisoformat(ss)
    print(int(dt.timestamp()))
    raise SystemExit
except Exception:
    pass

# Try "YYYY-MM-DD HH:MM:SS +0800" (podman StartedAt without tzname)
try:
    dt = datetime.datetime.strptime(s, "%Y-%m-%d %H:%M:%S %z")
    print(int(dt.timestamp()))
except Exception:
    print(0)
PY
    return
  fi

  echo 0
}

mkdir -p "$OUT_DIR"

{
  echo '# HELP podman_container_info Podman container info (id/name/image).'
  echo '# TYPE podman_container_info gauge'
  echo '# HELP podman_container_size_rw_bytes Podman container writable layer size in bytes.'
  echo '# TYPE podman_container_size_rw_bytes gauge'
  echo '# HELP podman_container_size_rootfs_bytes Podman container rootfs size in bytes.'
  echo '# TYPE podman_container_size_rootfs_bytes gauge'
  echo '# HELP podman_container_start_time_seconds Podman container start time in unix epoch seconds.'
  echo '# TYPE podman_container_start_time_seconds gauge'
  echo '# HELP podman_container_cpu_percent Podman container CPU usage percent (snapshot).'
  echo '# TYPE podman_container_cpu_percent gauge'
  echo '# HELP podman_container_mem_usage_bytes Podman container memory usage in bytes (snapshot).'
  echo '# TYPE podman_container_mem_usage_bytes gauge'
  echo '# HELP podman_container_mem_limit_bytes Podman container memory limit in bytes (snapshot).'
  echo '# TYPE podman_container_mem_limit_bytes gauge'
  echo '# HELP podman_container_net_rx_bytes_total Podman container total received bytes (since start, snapshot).'
  echo '# TYPE podman_container_net_rx_bytes_total counter'
  echo '# HELP podman_container_net_tx_bytes_total Podman container total transmitted bytes (since start, snapshot).'
  echo '# TYPE podman_container_net_tx_bytes_total counter'
  echo '# HELP podman_container_block_read_bytes_total Podman container total block read bytes (since start, snapshot).'
  echo '# TYPE podman_container_block_read_bytes_total counter'
  echo '# HELP podman_container_block_write_bytes_total Podman container total block write bytes (since start, snapshot).'
  echo '# TYPE podman_container_block_write_bytes_total counter'

  # Build a quick lookup map from container name -> stats fields (single snapshot).
  # We parse tab-separated output to avoid requiring jq.
  declare -A cpuP memUsageB memLimitB netRxB netTxB blkReadB blkWriteB
  while IFS=$'\t' read -r sName sCpuPerc sMemUsage sNetIO sBlockIO; do
    [[ -z "$sName" ]] && continue

    # CPU percent: strip trailing %
    localCpu="${sCpuPerc%\%}"
    localCpu="${localCpu:-0}"

    # MemUsage: "123MiB / 2GiB"
    sMemUsage="${sMemUsage:-0B/0B}"
    sMemUsage="${sMemUsage// /}"
    memLeft="${sMemUsage%%/*}"
    memRight="${sMemUsage#*/}"
    localMemUsageB="$(to_bytes "$memLeft")"
    localMemLimitB="$(to_bytes "$memRight")"

    # NetIO: "RX / TX"
    read -r localNetRxB localNetTxB <<<"$(split_io_pair_to_bytes "$sNetIO")"

    # BlockIO: "Read / Write"
    read -r localBlkReadB localBlkWriteB <<<"$(split_io_pair_to_bytes "$sBlockIO")"

    cpuP["$sName"]="$localCpu"
    memUsageB["$sName"]="$localMemUsageB"
    memLimitB["$sName"]="$localMemLimitB"
    netRxB["$sName"]="$localNetRxB"
    netTxB["$sName"]="$localNetTxB"
    blkReadB["$sName"]="$localBlkReadB"
    blkWriteB["$sName"]="$localBlkWriteB"
  done < <(podman stats --no-stream --format '{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}\t{{.BlockIO}}' 2>/dev/null || true)

  for cid in $(podman ps -q); do
    line="$(podman inspect --size --format '{{.Id}}\t{{.Name}}\t{{.Config.Image}}\t{{.SizeRw}}\t{{.SizeRootFs}}\t{{.State.StartedAt}}' "$cid")"
    IFS=$'\t' read -r id name image sizeRw sizeRootFs startedAt <<<"$line"
    [[ -n "$id" ]] || continue

    # podman inspect Name is usually "/xxx"
    name="${name#/}"
    [[ "$name" =~ $PODMAN_METRICS_NAME_REGEX ]] || continue

    short="${id:0:12}"
    startedAtSec="$(to_epoch_seconds "${startedAt:-}")"

    echo "podman_container_info{container_id=\"$(esc "$id")\",container_id_short=\"$(esc "$short")\",name=\"$(esc "$name")\",image=\"$(esc "$image")\"} 1"
    echo "podman_container_size_rw_bytes{container_id_short=\"$(esc "$short")\",name=\"$(esc "$name")\"} ${sizeRw:-0}"
    echo "podman_container_size_rootfs_bytes{container_id_short=\"$(esc "$short")\",name=\"$(esc "$name")\"} ${sizeRootFs:-0}"
    echo "podman_container_start_time_seconds{container_id_short=\"$(esc "$short")\",name=\"$(esc "$name")\"} ${startedAtSec:-0}"

    # stats (snapshots). Default to 0 if missing.
    echo "podman_container_cpu_percent{container_id_short=\"$(esc "$short")\",name=\"$(esc "$name")\"} ${cpuP["$name"]:-0}"
    echo "podman_container_mem_usage_bytes{container_id_short=\"$(esc "$short")\",name=\"$(esc "$name")\"} ${memUsageB["$name"]:-0}"
    echo "podman_container_mem_limit_bytes{container_id_short=\"$(esc "$short")\",name=\"$(esc "$name")\"} ${memLimitB["$name"]:-0}"
    echo "podman_container_net_rx_bytes_total{container_id_short=\"$(esc "$short")\",name=\"$(esc "$name")\"} ${netRxB["$name"]:-0}"
    echo "podman_container_net_tx_bytes_total{container_id_short=\"$(esc "$short")\",name=\"$(esc "$name")\"} ${netTxB["$name"]:-0}"
    echo "podman_container_block_read_bytes_total{container_id_short=\"$(esc "$short")\",name=\"$(esc "$name")\"} ${blkReadB["$name"]:-0}"
    echo "podman_container_block_write_bytes_total{container_id_short=\"$(esc "$short")\",name=\"$(esc "$name")\"} ${blkWriteB["$name"]:-0}"
  done
} >"$TMP"

mv "$TMP" "$OUT_FILE"
chmod 644 "$OUT_FILE"


