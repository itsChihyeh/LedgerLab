# T03 提现受理：模块、双门槛、API 与幂等

## 背景
ADR-0006 补充第 1、2、7 节；money-model 4.2；ADR-0004、0009；contracts 编号（`mpo_`）、金额、幂等与业务编号；P3 T05 退款 API 的实现（照着写）。

## 范围
1. 新模块 `payouts`（Modulith 边界；只依赖 ADR 列出的模块）。`Payouts.reserve(amount, merchantPayoutRef, fingerprint)`：资金锁、读余额、门槛、插 payout＋历史（evidence_type `api`）＋`PAYOUT_RESERVED`＋`SUBMIT_PAYOUT` 命令，同一事务。
2. `POST /api/payouts`（Idempotency-Key 必填）、`GET /api/payouts`、`GET /api/payouts/{id}`（本任务只返回 payout、历史、分录；尝试与流水在 T04/T05 补字段）；`GET /api/overview` 加 `payoutInTransit`、`freeCash`。
3. 错误：400 `INVALID_AMOUNT`、409 `INSUFFICIENT_FUNDS {reason}`、409 `PAYOUT_REF_CONFLICT`、幂等键冲突照退款；4xx 不留行。
4. money-model 4.2 补实现备注。

## AC / 测试场景
AC-7.1（受理部分：201 RESERVED、余额）、AC-7.2、AC-7.3、AC-7.4（账本级夹具）、AC-7.5、AC-7.15（栅栏并发两笔 6000）、AC-7.17、AC-7.20、AC-7.21。另：同幂等键重放返回原响应；两条门槛都不过报 `MERCHANT_BALANCE`；受理后命令 PENDING 且 request_id = payout id。

## 可改范围
`payouts/**`（新）、`ledger` 公开读接口只增不改、`investigation` 的 overview 读取、对应测试；`docs/design/money-model.md` 实现备注。

## 完成标准
按 `_common.md` 第 5 条；本地提交（`Task: T03`）。
