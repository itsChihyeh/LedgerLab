# T08 提现侧对账与老化

## 背景
ADR-0006 补充第 6 节；contracts 1.3（老化阈值 1 个工作日）；money-model INV-9、INV-11 与 P2 T11、P4 T04 实现备注；state-machines 第 7 节。依赖 T05。

## 范围
1. INV-9 加 2104；INV-11 的 1103 汇总加 `PAYOUT_PAID`、`PAYOUT_RETURNED`（按 booking_date 归期，旧证据时点重放只用当时的证据），以及纠错⑤ `CONFIRM_FAILED_PAYOUT_PAID` 的 `CORRECTION`（贷 1103；它的 subject 不是 bank_txn，要经纠错参数里的 bankTxnId 找到出账流水，按该流水 booking_date 归期）。`reconciliation/internal/BankEvidence.java` 按需要改。
2. BANK 段：`PAID`/`RETURNED` 提现与流水精确匹配，对不上报 `PAYOUT_STATE_CONFLICT`；规则版本 `p5-v1`（旧运行报告可读）。
3. AGING：`PAYOUT_UNRESOLVED`（含 RESERVED 超时），出账后自动解释/关闭照现有差异规则。
4. 实现备注写进 money-model / state-machines。

## AC / 测试场景
AC-7.7 的 `PAYOUT_UNRESOLVED` 部分、AC-6.15 的“纠错后银行余额核对不再报差”（T07 留下的断言）、AC-7.11 的 `BANK_BALANCE_MISMATCH` 差额、AC-7.18；提现全流程后银行余额核对不报差；退回当期 1103 与银行期末一致；旧证据时点重跑结果不变。

## 可改范围
`reconciliation/**`、`payouts` 公开读接口只增不改、对应测试；设计文档实现备注。

## 完成标准
按 `_common.md` 第 5 条；本地提交（`Task: T08`）。
