# T06 录制导出与脱敏、契约测试框架（S1）、check-payout

## 背景
stripe-integration.md 第 9.5、9.6 节、第 6 节“真实夹具验证”、第 3 节 P3 T09 备注里 `refund-updated-failed.json` 双份夹具的做法；T02 的录制表；T04/T05 产生的真实事件。AC-8.3（本任务先做 S1，T08 补齐其余场景）。

## 范围
1. simulator：`GET /sim/sandbox/recordings?kind=webhook|api&event_type=…&path_prefix=…&limit=…` 返回**已脱敏**的录制（sandbox profile 才有）。脱敏器是纯 Java 类，规则按 9.5；单测覆盖每条规则（含嵌套对象、数组、`acct_` 出现在字符串值中、邮箱、`client_secret`、`receipt_url`、`billing_details`、`fingerprint`），并有“扫描器”在输出含 `sk_`/`rk_`/`whsec_`/`_secret_`/`@`/`acct_`（REDACTED 除外）时抛错。
2. `scripts/sandbox/record-fixtures.sh --scenario s1`（之后 T08 扩展 s2/s3/s4）：从 recordings 取一条本 run 的 `charge.succeeded` 原文和对应 `GET /v1/balance_transactions`（若有）响应，写入 `src/test/resources/contracts/stripe-sandbox/s1-payment/`（文件名 `01-webhook-charge.succeeded.json` 这种），生成 `manifest.json`（场景、顺序、类型、`api_version`、录制时间、run_id、每个文件的期望映射值）。重跑覆盖同场景，写之前再用扫描器检查一遍。
3. `contracts/stripe-sandbox/used-fields.json`：按对象类型（event、charge、refund、payout、balance_transaction）列我们解析用到的字段路径和 JSON 类型——从现有解析代码（`StripeEventParser`、charge 准入、`RefundParser`、payout 解析、`RestStripeReader`）逐个核对出来，不凭印象。
4. 主应用契约测试 `StripeSandboxContractTest`（不联网）：遍历 `stripe-sandbox/*/manifest.json`，webhook 夹具喂解析/准入，断言映射值等于 manifest 期望；API 夹具由 loopback HttpServer 回放给 reader。另一个测试检查 `used-fields.json` 里每个字段在对应夹具里存在且类型一致。测试名带 `ac_8_3_`。
5. simulator 侧 `FakeStripeContractTest`：fake Stripe 跑 S1（建 charge），把产生的事件和对象与 simulator 测试资源里的同名夹具副本比较：事件类型序列一致、`used-fields.json` 列出的字段在 fake 输出里都存在且类型一致。主应用有一个测试比较两份夹具目录的 SHA-256 一致（沿用 P3 的做法）。fake 有差异：能修就修 fake（以真实为准）；修不了写进第 9.9 节已知差异表，测试里显式列为已知差异。
6. `scripts/sandbox/check-payout.sh`（给执行者用，也给 T07）：调 Stripe `GET /v1/payouts?created[gte]=<window_start>` 翻完分页；存在 `automatic=true` 的 payout 且 `status=paid`、`reconciliation_status=completed` → 打印 po_ 并退出 0；有 payout 但没走完 → 打印状态退出 75；一笔都没有 → 退出 75；Stripe 调用失败 → 退出 2。
7. 设计文档第 9.5、9.6 节补实现备注。

## 执行者和 Codex 都要实际跑
`sh scripts/sandbox/record-fixtures.sh --scenario s1`（产生的夹具提交进仓库）；`sh scripts/sandbox/check-payout.sh`（记录退出码和输出即可，75 是正常的）。

## 可改范围
`simulator/**`、`scripts/sandbox/**`、`src/test/**`（新契约测试与夹具）、`docs/design/stripe-integration.md`。主应用 `src/main` 不改；契约测试发现主应用解析与真实 Stripe 不符时停下来问（附差异）。

## 完成标准
按 `_common.md` 第 5 条：simulator verify；主应用 test-compile＋spotless:check＋新契约测试类＋ModularityTests。`git grep -nE "(rk|sk)_(test|live)_[A-Za-z0-9]|whsec_[A-Za-z0-9]|acct_[A-Za-z0-9]{4}"` 无输出。本地提交（`Task: T06`）。
