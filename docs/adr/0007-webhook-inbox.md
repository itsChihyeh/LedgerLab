# ADR-0007 webhook 先验签入库，再异步处理

状态：已采纳（P0）。来源：R6 定论。

## 背景
处理方 webhook 至少一次投递、不保证顺序，要求尽快返回 2xx（Stripe 最长重试 3 天）。同步处理会把我们的处理时间和失败暴露给对方，并让乱序事件只能被拒绝。

## 决定
1. 用原始请求体验 HMAC-SHA256 和时间戳窗口（±5 分钟）；失败返回 401，不入库。
2. 按 `event_id` 插入 `io.inbox_event`（永久唯一）；同 ID 同内容哈希直接 2xx，同 ID 不同内容记 `REJECTED` 并告警，仍返回 2xx 以免对方无限重试。
3. 返回 2xx。
4. 后台处理器加租约取事件，业务变更、分录和事件状态在同一事务提交。
5. 依赖对象还不存在的事件进 `PARKED`，在依赖对象创建的同一事务里被唤醒。

## 备选
同步处理：实现简单，测试直观；但乱序只能拒绝，依赖对方重试来“碰巧排对顺序”。

## 后果
API 层不再同步返回 `DUPLICATE`/`REJECTED`，这些结果改为在 inbox 状态里可查；AC-4 相应改写。不需要 Kafka。

## 补充（2026-10-21，随 ADR-0016）
- 入口是 `POST /webhooks/stripe`，签名用 Stripe 的方案（`Stripe-Signature`，HMAC-SHA256，时间窗口 5 分钟）；可以直接用 Stripe Java SDK 的验签函数。fake Stripe 用自己的密钥按同一方案签名。
- 端点只订阅需要的事件类型；不支持的资金类事件（如拒付）进 `REJECTED(UNSUPPORTED)` 并开差异，其他类型记 `IGNORED`；`livemode=true` 拒绝。
- 处理 `payout.created` 需要拉批次行：HTTP 调用在事务外完成，结果存 `io.processor_fetch` 后再进事务。
