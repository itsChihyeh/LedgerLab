# T08 补齐夹具（S2/S3/S4）、运行报告、P6 验收清单

## 背景
stripe-integration.md 第 9.5～9.9 节；AC-8.3、AC-8.10；`docs/handoff/p5.md`（验收清单格式）；T01～T07 的结论与 `target/sandbox/*.json`。

## 范围
1. `record-fixtures.sh` 支持 `--scenario s2|s3|s4|all`：S2 退款成功（T07 的 `POST /v1/refunds` 响应和 refund 事件）、S3 退款 pending（只有 T07 观察到 pending 才录，否则在 manifest 外的说明里写“测试模式未复现”）、S4 payout（payout.created → reconciliation_completed → paid 事件和 `balance_transactions?payout=` 的分页响应）。导出并提交；`StripeSandboxContractTest` 和 `FakeStripeContractTest` 自动覆盖新场景（fake 跑对应场景：退款、pending 退款、payout 生命周期）；差异按 T06 规则处理。
2. `scripts/sandbox/report.sh`：按 9.7 生成 `docs/runs/sandbox-<run_id>.md` 和 `.json`：Stripe 侧与本地两边的支付/退款/payout 数、状态分布；relay 两个方向各动作次数；resend 次数与仍缺失事件；不变量违反次数；对账发现；各 AC 最近一次 accept 结果；fake 与真实的已知差异（取第 9.9 节）。生成后用 T06 的扫描规则检查，不含密钥/acct_/邮箱。跑一次并提交报告。
3. `docs/handoff/p6.md`：P6 验收清单（每条 AC-8.3–8.6、8.8–8.10 → 测试或脚本、证据文件、结果），需要用户做的事（如有），已知限制（测试模式下 webhook 和结算单同源、评测数字只在模拟器模式报、payout 时间不可控等）。
4. README：在已有结构里加一小节“真实 Stripe 测试模式”，说明怎么跑和最新报告链接，数字只引用报告。`docs/design/phases.md` 当前状态改为 P6 完成（待用户审核）。
5. 清点 P6 留下的 TODO/待定，列在最后输出。

## 执行者和 Codex 都要实际跑
`sh scripts/sandbox/record-fixtures.sh --scenario all`、`sh scripts/sandbox/report.sh`。

## 可改范围
`scripts/sandbox/**`、`src/test/**`、`simulator/src/test/**`（以及修 fake 差异所需的 `simulator/src/main/**`）、`docs/**`、README。

## 完成标准
按 `_common.md` 第 5 条（simulator verify；主应用契约测试类＋ModularityTests）；`git grep` 密钥扫描无输出。本地提交（`Task: T08`）。

## 执行者验证
全量 verify、simulator verify、`sh scripts/smoke-p5.sh`、`sh scripts/sandbox/accept.sh --only ac-8.4`（回归），报告附结果。沙箱栈保持运行。
