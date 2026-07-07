#!/usr/bin/env bash
# Generate Hivirtus VIP license key for Telegram distribution
# Usage: ./tools/generate_key.sh [count]
set -euo pipefail

SECRET="HIVIRTUS_ZYGISK_VIP_97d6_LIQDY"
COUNT="${1:-1}"

gen_one() {
  local b1 b2 raw sum key
  b1=$(openssl rand -hex 2 | tr '[:lower:]' '[:upper:]')
  b2=$(openssl rand -hex 2 | tr '[:lower:]' '[:upper:]')
  raw="${b1}${b2}"
  sum=$(printf '%s' "$raw" | openssl dgst -sha256 -hmac "$SECRET" -hex | awk '{print toupper(substr($2,1,4))}')
  key="HIVIRTUS-${b1}-${b2}-${sum}"
  echo "$key"
}

echo "=== Hivirtus VIP Keys ==="
for ((i=1; i<=COUNT; i++)); do
  gen_one
done
echo "========================="
