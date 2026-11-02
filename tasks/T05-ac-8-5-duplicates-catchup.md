# T05 AC-8.5 真实的重复与乱序、漏收补投

## 背景
stripe-integration.md 第 9.2 节（补投）、第 1 节（relay）；T01 第 11 条结论（`stripe events resend` 能否投给 listen）；T04 的栈和 accept.sh。AC-8.5。

## 范围
1. `scripts/sandbox/catchup.sh`（＋python）：按 9.2：Events API 列窗口内订阅类型事件（翻完全部分页；任一页失败 → 输出“不完整”、不重投、退出 2）；逐个 `GET /api/events/{id}` 对照；缺的 `stripe events resend`（CLI 方式按 T01 结论），之后有界等待再对照一次，仍缺的写进 `.sandbox/missing-events.json`。随后调 `/sim/sandbox/bank/sync`，再把模拟银行到当天为止的流水按现有方式导入 app（照 smoke 脚本导入银行文件的做法：从 simulator 取 CSV，`POST /api/bank-statements`；已导入的同一文件版本 app 会去重，确认后再写）。整段幂等。输出摘要 `target/sandbox/catchup-<时间>.json`（resend 次数、仍缺失数、银行同步结果）。
2. `accept.sh --only ac-8.5`：
   a. 记录当前各支付状态和不变量基线（0 违反）。
   b. 对本 run 已 CAPTURED 的 3 个 `charge.succeeded` 事件执行 `stripe events resend`（每个 2 次）。
   c. 设置 relay 入站计划：`charge.succeeded` DUPLICATE copies=3；再生成 6 笔新支付（`traffic.sh --count 6 --seed 2`），其中借 HOLD_AND_REORDER（after_events=2）让事件乱序到达；完成后把计划恢复为空。
   d. 断言：重投/复制的事件在 inbox 只有一条业务结果（同一 evt 多次投递只新增投递记录）；每个 ch_ 只有一笔 payment、一条 `PAYMENT_CAPTURED` 分录（经 `/api/payments/{ref}` 的分录列表核对）；9 个事件全部最终 PROCESSED；不变量 0 违反；`GET /sim/relay/stats` 里 DUPLICATE 和 HOLD_AND_REORDER 次数与计划相符。
   e. 结果写 `target/sandbox/accept-<时间>.json`。
3. stripe-integration 第 9.2 节补实现备注（resend 的实际行为、等待时长）。

## 执行者和 Codex 都要实际跑
`sh scripts/sandbox/catchup.sh && sh scripts/sandbox/accept.sh --only ac-8.5`。本任务真实 PaymentIntent 预算 6 笔（加重跑最多 12）。保持栈运行。

## 可改范围
`scripts/sandbox/**`、`docs/design/stripe-integration.md`。需要改 Java 时停下来问。

## 完成标准
`python -m py_compile scripts/sandbox/*.py`；纯函数（分页合并、缺失对照）有不联网自检（`python scripts/sandbox/selftest.py` 之类）；上面的真实运行通过。本地提交（`Task: T05`）。

## 执行者验证
亲自跑 `sh scripts/sandbox/catchup.sh` 和 `sh scripts/sandbox/accept.sh --only ac-8.4`（回归）。不必重跑 ac-8.5（会再建支付）。
