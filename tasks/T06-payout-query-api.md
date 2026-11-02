# T06 运营查询提现（QUERY_PAYOUT）

## 背景
ADR-0006 补充第 4 节最后一条；ADR-0014/0017（运营接口在公开演示配置不注册）。依赖 T04。

## 范围
1. `POST /api/payouts/{id}/query`：202 返回命令号；已有未完成 QUERY_PAYOUT 返回它；状态不可查 409 `PAYOUT_NOT_QUERYABLE`；不存在 404。开关跟纠错接口同一套运营配置。
2. `QUERY_PAYOUT` 处理器：只查不发；结果按 T04 的写回规则；404 时按 ADR 处理（`SUBMIT_PAYOUT` 已 DONE → `PAYOUT_STATE_CONFLICT`；否则把 `SUBMIT_PAYOUT` 的 `next_attempt_at` 设为现在）。
3. state-machines 实现备注。

## AC / 测试场景
AC-7.23；查询得到 REJECTED 且无出账 → FAILED（与 AC-7.9 同结果）；查询得到 EXECUTED → 仍 ACCEPTED、无分录；公开演示配置下接口 404。

## 可改范围
`payouts/**`、对应测试；`docs/design/state-machines.md` 实现备注。

## 完成标准
按 `_common.md` 第 5 条；本地提交（`Task: T06`）。
