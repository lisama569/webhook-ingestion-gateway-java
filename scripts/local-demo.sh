#!/usr/bin/env bash
# 本地端到端验证脚本：先启动应用 (mvn spring-boot:run)，再运行本脚本。
# 用法: ./scripts/local-demo.sh [baseUrl]
set -u
BASE="${1:-http://localhost:8080}"
PARTNER="acme"
SECRET="acme-secret-key-2026"

# 用 openssl 生成 payloadHash(sha256 hex) 与 HMAC-SHA256(base64)
sign() {
  local partner="$1" ts="$2" body="$3" secret="$4"
  local hash mac
  hash=$(printf '%s' "$body" | openssl dgst -sha256 -hex | awk '{print $2}')
  mac=$(printf '%s' "${partner}.${ts}.${hash}" | openssl dgst -sha256 -hmac "$secret" -binary | openssl base64 -A)
  printf '%s' "$mac"
}

post() {
  local label="$1" partner="$2" secret="$3" ts="$4" body="$5" overrideSig="${6:-}"
  local sig; sig=$(sign "$partner" "$ts" "$body" "$secret")
  [ -n "$overrideSig" ] && sig="$overrideSig"
  echo "=== $label ==="
  curl -s -o .run/wh-resp.json -w 'HTTP %{http_code}\n' -X POST "$BASE/webhooks/events" \
    -H "Content-Type: application/json" \
    -H "X-Partner-Id: $partner" -H "X-Timestamp: $ts" -H "X-Signature: $sig" \
    -d "$body"
  cat .run/wh-resp.json; echo
}

TS=$(($(date +%s) * 1000))
BODY='{"eventId":"demo-1","eventType":"order.paid","version":1,"data":{"amount":100}}'

post "1) 合法首次投递(期望200 ACCEPTED)"   "$PARTNER" "$SECRET" "$TS" "$BODY"
post "2) 重复投递(期望200 DUPLICATE)"      "$PARTNER" "$SECRET" "$TS" "$BODY"
post "3) 时间戳过期(期望403 TIMESTAMP_EXPIRED)" "$PARTNER" "$SECRET" "$((TS-600000))" "$BODY"
post "4) 未知合作方(期望401 UNKNOWN_PARTNER)" "ghost"  "ghost-secret" "$TS" '{"eventId":"x","eventType":"t"}'
post "5) 签名错误(期望401 SIGNATURE_INVALID)" "$PARTNER" "$SECRET" "$TS" "$BODY" "dGFtcGVyZWQtc2lnbmF0dXJl"

echo "=== 6) 限流: globex 配额5/分钟，连发7次 ==="
for i in $(seq 1 7); do
  b="{\"eventId\":\"g-$i\",\"eventType\":\"order.paid\",\"version\":1}"
  t=$(($(date +%s) * 1000))
  s=$(sign globex "$t" "$b" globex-secret-key-2026)
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/webhooks/events" \
    -H 'Content-Type: application/json' -H 'X-Partner-Id: globex' \
    -H "X-Timestamp: $t" -H "X-Signature: $s" -d "$b")
  echo "globex #$i -> HTTP $code"
done

echo "=== 7) 死信查询: GET $BASE/webhooks/dead-letters ==="
curl -s "$BASE/webhooks/dead-letters"; echo
