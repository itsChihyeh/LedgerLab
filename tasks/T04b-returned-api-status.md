# T04b 银行查询返回 RETURNED 时结束命令（审查修复）

## 背景
T04 审查（Claude，2026-11-02）。`PayoutCommandHandler.result` 把银行 API 的 `RETURNED` 当成 `Unknown("BANK_RETURNED")`，命令会一直退避重试到上限再告警。ADR-0006 补充第 4 节：现金和退回只认银行流水，API 状态只推进到 `ACCEPTED`。`RETURNED` 说明银行已经受理并执行过，这条命令的工作已经完成。

## 范围
1. `SUBMIT_PAYOUT`／（T06 之后的 `QUERY_PAYOUT`）收到 `RETURNED`：与 `EXECUTED` 一样按“已受理”写回：payout 若仍在 `RESERVED/SUBMITTED/UNKNOWN` → `ACCEPTED`，已是 `ACCEPTED/PAID/RETURNED` 不动；不记任何分录、不释放资金、不唤醒退款队列；命令 `DONE`，`last_outcome = PAYOUT_RETURNED`。payout 为 `FAILED` 时按现有冲突规则开 `PAYOUT_STATE_CONFLICT`。
2. state-machines 第 3 节实现备注补一句。

## AC / 测试场景
- 银行先执行再退回（模拟器 `RETURN_AFTER_DAYS`），我们的命令在退回之后才第一次拿到结果（先 `UNAVAILABLE`）：命令 `DONE`、payout `ACCEPTED`、2104 仍是原金额、没有新分录；之后导入出账＋退回流水 → `PAID` → `RETURNED`（T05 完成后才能测到这一步的话，只测到 `ACCEPTED` 并在最后输出说明）。
- 原有 T04 测试全过。

## 可改范围
`payouts/**` 及其测试；`docs/design/state-machines.md` 实现备注。

## 完成标准
按 `_common.md` 第 5 条；本地提交（`Task: T04b`）。
