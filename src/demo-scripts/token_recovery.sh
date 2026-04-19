#!/usr/bin/env zsh
# Shows token bucket burst then gradual per-second recovery.
# Assumes /api/token-bucket/demo with limit=5, windowMs=10000
# Refill rate = 1 token per 2 seconds.

URL=${1:-http://localhost:8080/api/token-bucket/demo}

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'
BOLD='\033[1m';   DIM='\033[2m';      RESET='\033[0m'

total_latency_ms=0; req_count=0

fire() {
  local idx=$1 label=$2
  local ts=$(date "+%H:%M:%S")
  local http_status retry latency_ms time_raw curl_out

  curl_out=$(curl -s -o /dev/null -w "%{http_code} %{time_total}" \
    -H "X-Forwarded-For: demo-client" \
    -D /tmp/rl_theaders "$URL" 2>/dev/null)
  http_status=${curl_out%% *}
  time_raw=${curl_out##* }
  latency_ms=$(awk "BEGIN {printf \"%.0f\", $time_raw * 1000}")

  retry=$(grep -i "^retry-after:" /tmp/rl_theaders 2>/dev/null \
    | awk '{print $2}' | tr -d '\r\n')
  rm -f /tmp/rl_theaders

  # Accumulate into outer scope variables (no local — intentional)
  (( total_latency_ms += latency_ms ))
  (( req_count++ ))

  if [[ $http_status == "200" ]]; then
    printf "  [%s]  req=%02d  %-22s  ${GREEN}${BOLD}%s  ✓ allowed${RESET}    %sms\n" \
      "$ts" "$idx" "$label" "$http_status" "$latency_ms"
  else
    printf "  [%s]  req=%02d  %-22s  ${RED}${BOLD}%s  ✗ rate limited${RESET}  retry_after=${retry}s    %sms\n" \
      "$ts" "$idx" "$label" "$http_status" "$latency_ms"
  fi
}

echo ""
echo "  ${BOLD}TOKEN_BUCKET   policy=5 req / 10s   refill=1 token per 2s${RESET}"
echo "  ${DIM}$(printf '─%.0s' {1..60})${RESET}"
echo "  ${DIM}>>> phase 1: empty the bucket with a burst${RESET}"
for i in {1..6}; do fire $i "burst"; done

echo ""
echo "  ${CYAN}  bucket is empty — watching gradual refill (1 token per ~2s)${RESET}"
echo ""
echo "  ${DIM}>>> phase 2: one request every 1s — tokens refill between requests${RESET}"
for i in {7..17}; do
  fire $i "recovery (every 1s)"
  (( i < 17 )) && sleep 1
done

avg_ms=$(awk "BEGIN {printf \"%.1f\", $total_latency_ms / $req_count}")

echo "  ${DIM}$(printf '─%.0s' {1..60})${RESET}"
echo "  ${BOLD}Token bucket recovered gradually — no hard reset.${RESET}"
printf "  ${BOLD}summary: total_latency=%dms  avg_latency=%sms\n\n${RESET}" \
  "$total_latency_ms" "$avg_ms"
