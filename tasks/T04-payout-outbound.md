# T04 提现出站：银行网关与 SUBMIT_PAYOUT 处理

## 背景
ADR-0006 补充第 4、7 节；ADR-0008；state-machines 第 3、6 节；P3 T07/T08 的退款网关与 `CommandHandler`（照着写）。依赖 T01（模拟银行接口）、T03。

## 范围
1. `payouts` 里 SPI `BankPayoutGateway`（submit/query）＋ HTTP 实现（调模拟银行，超时可配 `ledgerlab.bank.payments.*`），没有 bean 时不注册处理器。
2. `SUBMIT_PAYOUT` 的 `CommandHandler`：首次 submit；重试/接管先 query，404 再用同一 requestId submit；写回规则（ACCEPTED/EXECUTED → ACCEPTED；REJECTED 且无出账 → FAILED＋`PAYOUT_FAILED`＋`RefundQueue.release()`；超时/5xx → UNKNOWN；已 PAID 收到拒绝 → `PAYOUT_STATE_CONFLICT`）。每次网关操作照 P3 记 `io.outbound_attempt`。
3. payout 详情接口补命令与尝试的脱敏摘要（有现成的退款视图可仿照）。
4. state-machines 第 3 节补实现备注（新增 RESERVED/UNKNOWN → ACCEPTED 直接边）。

## AC / 测试场景（测试里起真实模拟器或用与模拟器同协议的测试替身——优先跟 P3 T08 的做法一致）
AC-7.1（ACCEPTED 部分）、AC-7.6、AC-7.7（UNAVAILABLE ≥ 10、时钟前进 5 个工作日仍 UNKNOWN、在途不变；`PAYOUT_UNRESOLVED` 的老化部分留 T08，这里只断言状态和余额）、AC-7.8（EXECUTE_DROP_RESPONSE → UNKNOWN → 重试 query 得到原付款 → ACCEPTED，模拟银行只有一笔）、AC-7.9（REJECT_ON_QUERY：先超时、再查询得到 REJECTED → FAILED、`reverses_entry_id` 为空、退款队列重算）、AC-7.16（两个崩溃点，用 OutboundHooks）、AC-7.22。另：旧租约迟到回执不改 payout。

## 可改范围
`payouts/**`、`outbound` 公开接口只增不改、配置文件、对应测试；`docs/design/state-machines.md` 实现备注。

## 完成标准
按 `_common.md` 第 5 条；本地提交（`Task: T04`）。
