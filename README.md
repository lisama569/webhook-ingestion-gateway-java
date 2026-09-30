# 对外事件回调接入网关（Webhook Ingestion Gateway）

本地可运行的合作方事件回调接入能力。合作方推送的业务事件在**重复、乱序、并发**
情况下都能得到一致、可解释的处理结果：

- 签名 + 时效（防重放）+ 合作方身份校验，拒绝原因可区分；
- 按事件标识做幂等与并发控制，同一事件只生效一次；
- 乱序晚到不让已确认状态回退，冲突给出明确结论而非静默覆盖；
- 按合作方独立限流（每分钟固定窗口），失败投递有限次同步重试；
- 重试耗尽的投递进入**死信**保留，可通过接口查询。

## 1. 目录与模块

```
config/   WebhookProperties（可配置项）、GatewayConfig（Clock bean）
crypto/   HmacSigner：SHA-256 与 HMAC-SHA256 签名/常量时间校验
security/ CallbackAuthenticator：合作方、时效、签名校验
ratelimit/PartnerRateLimiter：每合作方每分钟固定窗口配额
storage/  EventRecordStore（幂等/乱序/冲突/并发控制）、DeadLetterStore（死信）
retry/    RetryExecutor：有限次重试 + 固定退避
handler/  BusinessEventHandler（业务处理 SPI）、NoopBusinessEventHandler（默认实现）
service/  DefaultWebhookIngestionService：编排主流程
web/      WebhookController（接收端点 + 死信查询）、异常处理
```

## 2. 签名与防重放规则

每个合作方在配置中拥有**独立的密钥**（`webhook.partners.<id>.secret`），互相不可冒用。

请求头：

| 头 | 含义 |
| --- | --- |
| `X-Partner-Id` | 合作方标识 |
| `X-Timestamp` | 发送时刻，epoch **毫秒** |
| `X-Signature` | 对请求体的签名 |

签名算法（HMAC-SHA256，输出标准 Base64）：

```
payloadHash = hex_lower( SHA256( 请求体原文 ) )
signContent = partnerId + "." + timestamp + "." + payloadHash
signature   = base64( HMAC_SHA256( secret, signContent ) )
```

签名必须基于**请求体原文**（字段顺序、空白都不能变），服务端用常量时间比较。

防重放：服务端时钟与 `X-Timestamp` 的绝对偏差超过
`webhook.replay-window-seconds`（默认 300 秒）即拒绝；过期与“来自未来”的请求都拒绝。

校验顺序与可区分拒绝原因：

1. 合作方未登记 / 密钥未知 → `UNKNOWN_PARTNER`（HTTP 401）
2. 时间戳超出窗口 → `TIMESTAMP_EXPIRED`（HTTP 403）
3. 签名不匹配 → `SIGNATURE_INVALID`（HTTP 401）
4. 鉴权通过后再占用配额；超出配额 → `RATE_LIMITED`（HTTP 429）

> 注意：先鉴权后限流，未知合作方无法消耗已登记合作方的配额。

## 3. 幂等键约定

```
idempotency-key = partnerId + ":" + eventId
```

- `eventId` 由合作方保证在其维度内全局唯一，放在请求体的 `eventId` 字段；
- 同一幂等键 + 相同载荷哈希（`SHA-256` 规范化 JSON）视为同一次投递；
- 不同合作方即使使用相同 `eventId` 也互不影响。

请求体示例：

```json
{
  "eventId": "order-123-paid",
  "eventType": "order.paid",
  "version": 1,
  "timestamp": 1759267200000,
  "data": { "orderId": "123", "amount": 100 }
}
```

`eventId`、`eventType` 必填；`version` 缺省为 0；`timestamp` 缺省取请求头时间。

## 4. 事件状态流转与处理结论

状态机（`EventStatus`）：

```
RECEIVED ──> PROCESSING ──> CONFIRMED        （首次投递处理成功）
                        ──> DEAD_LETTER      （重试耗尽，同时保留死信）
CONFLICT / STALE_VERSION 不产生新状态，保持既有状态不变
```

处理结论（`Disposition`，全部可区分，不做静默覆盖）：

| 结论 | 触发条件 | HTTP |
| --- | --- | --- |
| `ACCEPTED` | 首次投递且业务处理成功（含重试后成功） | 200 |
| `DUPLICATE` | 同幂等键且载荷哈希一致（重复/并发/重试重放） | 200 |
| `STALE_VERSION` | 载荷不同且 `version` **低于**已存版本（乱序晚到），状态不回退 | 200 |
| `CONFLICT` | 同幂等键载荷不同且版本相同或更高——同一事件标识不允许被不同内容改写 | 409 |
| `RETRY_EXHAUSTED` | 达到 `webhook.max-attempts` 仍失败，已落死信 | 503 |
| `REJECTED` | 安全或配额校验未通过，见 `rejectionReason` | 401/403/429 |

乱序处理约定：业务方对同一实体的更新若内容不同，应使用**新的 `eventId`**
并带递增 `version`；复用同一 `eventId` 推送不同内容会被判为 `CONFLICT`。
只有在“同事件、同载荷”时版本号高低不影响，统一判 `DUPLICATE`。

并发控制：`EventRecordStore` 对每个幂等键使用独立锁，把同一事件的并发插入
串行化判定，保证并发推送时恰好一次 `ACCEPTED`，其余全部 `DUPLICATE`，
业务处理器只被调用一次。

## 5. 限流与重试

- 限流：每合作方独立的**每分钟固定窗口**，配额由
  `webhook.partners.<id>.quota-per-minute` 配置；窗口对分钟对齐自动翻转。
- 重试：业务处理器 `BusinessEventHandler#handle` 抛异常即视为失败，
  由 `RetryExecutor` 最多执行 `webhook.max-attempts` 次（含首次），
  每次失败后固定退避 `webhook.retry-backoff-millis` 毫秒。
- 重试耗尽：事件置 `DEAD_LETTER`，原始载荷、尝试次数、最后错误原样写入死信存储，
  不会丢弃，可查询。

死信查询端点：

```
GET /webhooks/dead-letters            # 全部死信（按创建时间排序）
GET /webhooks/dead-letters/{id}       # 按死信 ID 查询
```

## 6. 可配置项（application.properties）

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | 8080 | 监听端口 |
| `webhook.replay-window-seconds` | 300 | 时间戳允许偏差（防重放窗口） |
| `webhook.max-attempts` | 3 | 最大投递尝试次数（含首次） |
| `webhook.retry-backoff-millis` | 50 | 重试固定退避毫秒 |
| `webhook.partners.<id>.secret` | （必填） | 合作方独立 HMAC 密钥 |
| `webhook.partners.<id>.quota-per-minute` | （必填） | 合作方每分钟请求配额 |

内置两个示例合作方：`acme`（100/分钟）与 `globex`（5/分钟）。
接入自定义业务处理：实现 `BusinessEventHandler` 并注册为 Spring Bean
（默认的 `NoopBusinessEventHandler` 直接成功）。

## 7. 本地验证方法

### 7.1 运行测试（覆盖要求的全部场景）

```bash
mvn test
```

共 23 个用例，覆盖：

| 测试类 | 场景 |
| --- | --- |
| `HmacSignerTest` | 签名正确通过；篡改载荷/时间戳/密钥失败 |
| `AuthenticationTest` | 未知合作方、时间戳过期、未来时间戳、签名错误、冒用他人密钥 |
| `IdempotencyTest` | 重复投递只生效一次；跨合作方幂等键独立 |
| `ConcurrentDeliveryTest` | 32 线程并发投递同一事件，恰好一次生效 |
| `OutOfOrderAndConflictTest` | 乱序晚到不回退；同版本/高版本不同载荷判冲突 |
| `RateLimitTest` | 超配额拒绝；合作方配额互相独立 |
| `RetryExhaustionTest` | 重试到上限落死信且可查询；瞬时失败重试内成功 |
| `WebhookEndToEndTest` | HTTP 端到端：状态码区分、死信查询端点 |

单测日志统一打印 `eventId=`、`eventPartner=`、`判定=` 与 `依据=`，例如：

```
判定=DUPLICATE eventId=e-dup-1 eventPartner=acme 依据=同幂等键且载荷哈希一致,当前状态=CONFIRMED
判定=RETRY_EXHAUSTED eventId=e-retry-1 eventPartner=acme attempts=3 deadLetterId=dl-... 依据=重试达上限仍失败
```

### 7.2 本地起服务 + curl 演示

```bash
mvn spring-boot:run            # 终端 1
./scripts/local-demo.sh        # 终端 2（需要 openssl 与 curl）
```

脚本依次演示：合法投递、重复投递、过期时间戳、未知合作方、错误签名、
globex 超配额（第 6、7 次返回 429）以及死信查询。

手工生成签名（bash/openssl）：

```bash
BODY='{"eventId":"order-123-paid","eventType":"order.paid","version":1}'
TS=$(($(date +%s)*1000))
HASH=$(printf '%s' "$BODY" | openssl dgst -sha256 -hex | awk '{print $2}')
SIG=$(printf '%s' "acme.${TS}.${HASH}" \
  | openssl dgst -sha256 -hmac acme-secret-key-2026 -binary | openssl base64 -A)
curl -X POST http://localhost:8080/webhooks/events \
  -H 'Content-Type: application/json' \
  -H "X-Partner-Id: acme" -H "X-Timestamp: $TS" -H "X-Signature: $SIG" \
  -d "$BODY"
```

## 8. 说明与边界

- 存储为进程内内存实现（重启清空），适合本地验证；生产可替换 `EventRecordStore`
  /`DeadLetterStore` 为数据库实现，键级锁可替换为数据库唯一约束 + 乐观锁。
- 限流为单实例固定窗口；多实例部署需改为集中式（如 Redis）计数。
- 重试为投递线程内同步重试；如需异步重投可在死信之上增加调度重放。
