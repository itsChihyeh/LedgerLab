# P6 暂停状态与恢复指南（2026-11-03）

P6（真实 Stripe 测试模式）做到一半时暂停：用户的 Codex 会员到期，本机执行停止。本文件说明停在哪、有哪些证据、怎么接着做。设计正本是 [stripe-integration 第 9 节](../design/stripe-integration.md#9-p6-真实沙箱运行开工设计2026-11-02)，开户实测记录在 [p6-probe.md](p6-probe.md)。

## 1. 已完成（`p6` 分支，T01–T06）

| 任务 | 内容 | AC | 结果 |
|---|---|---|---|
| T01 | `scripts/sandbox/env.sh`（只取 `.env` 的 `STRIPE_API_KEY`）、Stripe CLI 1.55.6、开户假设实测 | 前置 | 密钥可用、测试模式；加拿大卡手续费 = 30 + round(2.9%)；美国卡（bypassPending）多 10 分；事件版本 `2026-09-30.endive`；受限密钥不能 `stripe listen`，用户已 `stripe login`（选“测试企业 沙盒”，已核对同一账号） |
| T02 | chaos relay 出站代理 `/relay/stripe-api/**`、出站规则 DROP_RESPONSE / FAIL_BEFORE_SEND、录制表、`/sim/relay/stats` | AC-8.6 前置 | 主应用 1802/0，模拟器 188/0 |
| T03 | simulator `sandbox` 包：流量生成器、模拟银行接真实 paid payout、时钟跟随现实 | 前置 | 模拟器 227/0 |
| T04 | `compose.sandbox.yaml`、`scripts/sandbox/{up,down,status,traffic,accept}`、`GET /api/audit/invariants` | **AC-8.4 通过** | 20 笔真实测试支付经 `stripe listen` → relay → app 全部 `CAPTURED`；篡改正文、缺签名头、过期时间戳均 401；不变量违反 0；全量 1805/0 |
| T05 | `catchup.sh`（Events API 翻页对照＋`stripe events resend` 补投＋银行同步与导入） | **AC-8.5 通过** | 真实重投＋relay 复制 3 份＋扣留乱序：9 个事件各只一次业务结果、每个 charge 一笔 payment 一条分录，不变量 0。首次运行因脚本判据错误 FAIL，修正后两次 PASS（复用同一批事件）；首次 catchup 补回 1 个漏收事件 |
| T06 | 录制导出与脱敏、`used-fields.json`、`StripeSandboxContractTest`、`FakeStripeContractTest`、`check-payout.sh` | **AC-8.3 部分**（S1 支付） | 主应用 1809/0，模拟器 254/0 |

真实沙箱里一共建了 28 笔测试支付（T01 探测 2 笔、T04 20 笔、T05 6 笔），0 笔退款。

## 2. 没做完

| 任务 | 内容 | AC | 为什么停 |
|---|---|---|---|
| T07 | 真实 payout 即批次、退款走真实 Stripe（含丢回执）、对账接真实数据 | AC-8.8、AC-8.6、AC-8.9 | 要等 Stripe 第一笔自动 payout（新账号有初始等待期）；暂停时 payout 列表仍为空。退款要等批次结算后商户才有可用款，所以排在 payout 之后 |
| T08 | 补齐夹具 S2/S3/S4、运行报告 `docs/runs/`、P6 验收清单 | AC-8.3 其余、AC-8.10 | 依赖 T07 |

任务说明在 `p6-tasks` 分支（`tasks/T07-*.md`、`tasks/T08-*.md`），审查答复在 `answers/`。本机执行报告在 `p6-reports` 分支（`STOP.md` 记停止时的状态）。

## 3. 停止时的本机状态

- 沙箱栈已 `down.sh`（**保留卷**和 `.sandbox/`：run.json、本地生成的数据库密码、listen 签名密钥都在里面，已被 gitignore）。不要 `--purge`，否则账本里的 28 笔支付和录制就没了。
- 后台 `stripe listen` 已停。Stripe CLI 登录状态保留在用户电脑上。
- 不再调用 Codex。停止前最后一次 `check-payout`：0 笔 payout，测试余额可用 306651 分、待结算 9680 分（CAD）。
- 用户电脑上另有 Codex 桌面应用自身的进程，不属于本项目，没动。

## 4. 恢复步骤（不需要 Codex，Claude 本机执行即可）

1. `sh scripts/sandbox/up.sh`（会重新起容器和 `stripe listen`，核对 CLI 账号）。
2. `sh scripts/sandbox/catchup.sh`：Events API 保留 30 天，停机期间的 payout 和支付事件会补投；必须在 **2026-12-02 之前**恢复，否则最早的事件会超出保留期。
3. `sh scripts/sandbox/check-payout.sh`：退出 0 才能做 T07；75 表示还在等。
4. T07：按 `p6-tasks` 的任务说明实现 `accept.sh --only ac-8.8|ac-8.6|ac-8.9` 并真实跑；T08：补夹具、`report.sh`、验收清单 `docs/handoff/p6.md`。
5. 全量 `./mvnw verify`、`./mvnw -f simulator/pom.xml verify`、`sh scripts/smoke-p5.sh` 后开 PR。

## 5. 待注意

- 美国测试卡的实际手续费比合同公式多，批次会按实际 fee 记账；对账可能因此报手续费差异，属于真实发现，不是缺陷。
- 第 7 节第 1、4、8 条（自动 payout、余额不足退款 pending、reconciliation_completed）仍待 T07 观察。
- `p6` 基于 `p5`；P5 的 PR #2 合并后，P6 的 PR 直接对 `main`。
