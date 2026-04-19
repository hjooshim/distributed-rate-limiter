#!/usr/bin/env zsh
# Usage: ./scripts/demo.sh <url> [count=8] [delay_ms=300]
# Example: ./scripts/demo.sh http://localhost:8080/api/strict 8 200

URL=${1:?'Usage: demo.sh <url> [count] [delay_ms]'}
COUNT=${2:-8}
DELAY_MS=${3:-300}

# Derive algorithm label and policy from URL — no guessing from headers
if [[ $URL == *"sliding-window"* ]]; then
  ALGO="SLIDING_WINDOW"
  POLICY="3 req / 10s"
elif [[ $URL == *"token-bucket/demo"* ]]; then
  ALGO="TOKEN_BUCKET  "
  POLICY="5 req / 15s"
elif [[ $URL == *"token-bucket"* ]]; then
  ALGO="TOKEN_BUCKET  "
  POLICY="1 req / 60s"
else
  ALGO="FIXED_WINDOW  "
  POLICY="3 req / 10s"
fi

# ANSI colors
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BOLD='\033[1m';   DIM='\033[2m';      RESET='\033[0m'

echo ""
echo "  ${BOLD}algo=${ALGO}   policy=${POLICY}   sending ${COUNT} requests${RESET}"
echo "  ${DIM}$(printf '─%.0s' {1..60})${RESET}"

allowed=0; rejected=0; total_latency_ms=0

for i in $(seq 1 $COUNT); do
  ts=$(date "+%H:%M:%S")
  tmpfile=$(mktemp /tmp/rl_demo.XXXXXX)

  # Capture status code and total request time in one curl call
  curl_out=$(curl -s -o "$tmpfile" -w "%{http_code} %{time_total}" \
    -H "X-Forwarded-For: demo-client" \
    -D /tmp/rl_headers "$URL" 2>/dev/null)
  http_status=${curl_out%% *}
  time_raw=${curl_out##* }
  latency_ms=$(awk "BEGIN {printf \"%.0f\", $time_raw * 1000}")
  (( total_latency_ms += latency_ms ))

  retry=$(grep -i "^retry-after:" /tmp/rl_headers 2>/dev/null \
    | awk '{print $2}' | tr -d '\r\n')

  if [[ $http_status == "200" ]]; then
    (( allowed++ ))
    printf "  [%s]  req=%02d  algo=%s  ${GREEN}${BOLD}%s  ✓ allowed${RESET}    %sms\n" \
      "$ts" "$i" "$ALGO" "$http_status" "$latency_ms"

  elif [[ $http_status == "429" ]]; then
    (( rejected++ ))
    printf "  [%s]  req=%02d  algo=%s  ${RED}${BOLD}%s  ✗ rate limited${RESET}  retry_after=${retry}s    %sms\n" \
      "$ts" "$i" "$ALGO" "$http_status" "$latency_ms"

  elif [[ $http_status == "503" ]]; then
    printf "  [%s]  req=%02d  algo=%s  ${YELLOW}${BOLD}%s  ⚠ backend unavailable${RESET}    %sms\n" \
      "$ts" "$i" "$ALGO" "$http_status" "$latency_ms"

  else
    printf "  [%s]  req=%02d  algo=%s  status=%s    %sms\n" \
      "$ts" "$i" "$ALGO" "$http_status" "$latency_ms"
  fi

  rm -f "$tmpfile" /tmp/rl_headers
  sleep $(echo "scale=3; $DELAY_MS / 1000" | bc)
done

avg_ms=$(awk "BEGIN {printf \"%.1f\", $total_latency_ms / $COUNT}")

echo "  ${DIM}$(printf '─%.0s' {1..60})${RESET}"
printf "  ${BOLD}summary: ${GREEN}%d allowed${RESET}  ${RED}%d rejected${RESET}  |  total_latency=%dms  avg_latency=%sms\n\n" \
  "$allowed" "$rejected" "$total_latency_ms" "$avg_ms"
