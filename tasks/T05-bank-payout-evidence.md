# T05 银行导入里的提现证据（出账、退回、冲突）

## 背景
ADR-0006 补充第 5 节是正本；money-model 第 3 节银行匹配与 P2 T08、P4 T04 实现备注；AC-7.13 等。依赖 T03（payout 已存在）。

## 范围
1. `payouts` 公开窄接口 `PayoutBankEvidence`（在调用方事务、已持资金锁内执行）：`applyDebit(line)`、`applyCredit(line)`，返回处理结果供 `settlement` 设置流水 `match_status` 和开差异。
2. `settlement` 银行导入按 reference 前缀分流 `mpo_`，其余规则不变；`PAYOUT_PAID`、`PAYOUT_RETURNED` 分录、payout 状态与历史（evidence_type `bank_txn`）、流水状态、差异（`PAYOUT_STATE_CONFLICT`、`UNMATCHED_BANK_LINE` 带候选）同事务。退回成功后调 `RefundQueue.release()`。
3. 出账匹配后关闭该 payout 的 OPEN `PAYOUT_UNRESOLVED`（如已存在）。
4. payout 详情接口补关联银行流水行。
5. money-model 第 3 节补实现备注。

## AC / 测试场景
AC-7.1（PAID 部分：全部为 0）、AC-7.10、AC-7.11（`BANK_BALANCE_MISMATCH` 的断言如果依赖 T08 的规则版本，可只断言不记账与 `PAYOUT_STATE_CONFLICT`，在最后输出说明）、AC-7.12、AC-7.13（两个文件版本＋重复导入）、AC-7.24、AC-7.25、AC-7.26（退回唤醒）。另：PENDING 出账只存档，PENDING→BOOKED 后才 PAID；`mpo_` 与 `po_` 与入账流水号引用互不干扰；并发导入同一文件只记一次。现有银行 jqwik 模型加“提现出账/退回”动作（需要的 payout 用账本夹具或 API 建立）。

## 可改范围
`payouts/**`、`settlement/**`（银行导入）、`reconciliation` 公开差异入口只增不改、对应测试；`docs/design/money-model.md` 实现备注。

## 完成标准
按 `_common.md` 第 5 条；本地提交（`Task: T05`）。
