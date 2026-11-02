# T07 纠错⑤ CONFIRM_FAILED_PAYOUT_PAID

## 背景
ADR-0011 第 8 节是正本，第 1 节总规则照旧；P4 `corrections` 模块的 `RESTORE_FAILED_PAYOUT`（结构最像）。依赖 T05（冲突证据场景）。

## 范围
1. `corrections` 加第五种类型：前置顺序、分录（REVERSAL 冲销 `PAYOUT_FAILED` ＋ CORRECTION 借 2104 / 贷 1103）、payout `FAILED → PAID`（`payouts` 公开接口只增一个窄方法）、流水 `PAYOUT_PAID`＋`matched_payout_id`、自动关闭差异、加锁顺序，全部同事务。1103 为负被数据库拒绝 → 409 `CASH_WOULD_GO_NEGATIVE`、无写入。
2. `PAYOUT_FAILED` 只允许被本类型冲销，其余类型对它仍 `ENTRY_NOT_CORRECTABLE`。
3. 纠错详情页面数据（现有接口）能显示新类型（字段齐即可，页面样式留 T10）。
4. ADR-0011 第 8 节补实现备注。

## AC / 测试场景
AC-6.15（含重复提交 409、每条前置 4xx 无写入，用完整快照比较）；纠错后商户可用为负时页面数据显示欠款；纠错后再来退回 CREDIT → 正常 `PAYOUT_RETURNED`。P4 的纠错 jqwik 模型不用改（提现动作在 T09 加）。

## 可改范围
`corrections/**`、`payouts` 公开接口只增不改、对应测试；`docs/adr/0011-restricted-correction.md` 实现备注。

## 完成标准
按 `_common.md` 第 5 条；本地提交（`Task: T07`）。
