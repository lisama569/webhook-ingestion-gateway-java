# Webhook Ingestion Gateway

本地可运行的对外事件回调接入服务。合作方推送的业务事件在**重复、乱序、并发**情况下都能得到一致、可解释的处理结果。

## 接口

### POST /webhooks/{partnerId}

请求头：

| 头 | 说明 |
|---|---|
| `X-Timestamp` | 客户端 Unix 秒级时间戳 |
| `X-Signature` | 签名（见下文） |

请求体：

```json
{ "eventId": "evt-123", "eventSeq": 1, "eventType": "order.created", "payload": "{...}" }
```

### GET /webhooks/dead-letters

查询重试耗尽后保留的失败投递（死信），不会被丢弃。

## 签名与防重放规则

1. 每个合作方有独立密钥（`webhook.partners.<id>.secret`）。
2. 规范化报文：`canonical = eventId + "|" + eventSeq + "|" + eventType + "|" + payload`
3. 签名：`X-Signature = hex(HMAC-SHA256(secret, partnerId + "\n" + timestamp + "\n" + canonical))`
4. 时间戳与服务器时钟偏差超过 `webhook.timestamp-tolerance-seconds`（默认 300s）即拒绝，防止重放。
5. 拒绝原因可区分（响应体 `reason` 字段 + HTTP 状态码）：

| reason | HTTP | 含义 |
|---|---|---|
| `UNKNOWN_PARTNER` | 401 | 未配置该合作方密钥 |
| `INVALID_SIGNATURE` | 401 | 签名校验失败 |
| `TIMESTAMP_EXPIRED` | 400 | 时间戳超出容忍窗口 |
| `QUOTA_EXCEEDED` | 429 | 超出该合作方当前窗口配额 |

## 幂等键约定与判定结论

幂等键 = `partnerId + ":" + eventId`。同一键的处理在服务端按 key 串行化，并发安全。判定结论（响应体 `result` 字段）：

| result | HTTP | 含义 |
|---|---|---|
| `APPLIED` | 200 | 首次到达或序号推进，已生效 |
| `DUPLICATE` | 200 | 同序号同内容，幂等成功，不重复生效 |
| `STALE_REJECTED` | 409 | 乱序/晚到（序号小于已确认序号），状态不回退 |
| `CONFLICT` | 409 | 同序号但内容不同，不静默覆盖，需人工介入 |

## 事件状态流转

```
RECEIVED --投递成功--> CONFIRMED
RECEIVED --投递失败--> DELIVERY_FAILED --(重试, 最多 max-retry-attempts 次)--> CONFIRMED
DELIVERY_FAILED --重试耗尽--> DEAD_LETTERED (写入死信存储, 可查询)
```

乱序到达的事件（`eventSeq` 小于已确认序号）不会改变已确认状态。

## 可配置项（application.properties）

| 配置 | 默认 | 说明 |
|---|---|---|
| `webhook.timestamp-tolerance-seconds` | 300 | 签名时间戳容忍窗口 |
| `webhook.max-retry-attempts` | 3 | 投递最大尝试次数（含首次） |
| `webhook.rate-window-seconds` | 60 | 限流窗口长度 |
| `webhook.partners.<id>.secret` | — | 合作方密钥 |
| `webhook.partners.<id>.quota-per-window` | — | 合作方每窗口配额 |

## 本地验证

```bash
# 运行全部测试（覆盖重复投递、并发同一事件、乱序、签名过期、超配额、重试耗尽）
mvn test

# 启动服务
mvn spring-boot:run

# 构造签名并推送事件（以 acme / acme-secret-key 为例）
TS=$(date +%s)
CANONICAL='evt-1|1|order.created|{"a":1}'
SIG=$(printf 'acme\n%s\n%s' "$TS" "$CANONICAL" | openssl dgst -sha256 -hmac 'acme-secret-key' | awk '{print $2}')
curl -s -X POST localhost:8080/webhooks/acme \
  -H "X-Timestamp: $TS" -H "X-Signature: $SIG" \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"evt-1","eventSeq":1,"eventType":"order.created","payload":"{\"a\":1}"}'

# 查询死信
curl -s localhost:8080/webhooks/dead-letters
```

单测日志会打印事件标识、合作方与判定依据（`eventId=... partnerId=... outcome=... rationale=...`）。
