# 暂停状态与恢复指南（停在 P4）

2026-10-25。P0 设计到 P4 纠错全部完成，`main` = `3eb3cac`（Merge PR #18）。项目在这里暂停，下一阶段 P5 提现还没开工。本文件说明停在哪、还欠什么、怎么重新开工。

## 1. 停在哪

| 项 | 状态 |
|---|---|
| 阶段 | P0、P1（a/b/c）、P2、P3、P4 完成；验收清单 [p1.md](p1.md)、[p2.md](p2.md)、[p3.md](p3.md)、[p4.md](p4.md) |
| `main` | `3eb3cac`；PR #1～#18 全部合并，没有打开的 PR 和 issue |
| 测试规模 | 主应用 1556、模拟器 122、前端单测 25、Playwright 11；smoke-p2、smoke-p4 通过 |
| 迁移 | 主应用 V1～V20，模拟器 V1～V5 |
| 本机仓库 | 在 `main`、工作区干净；两个报告 worktree（`..\LedgerLab-reports`、`..\LedgerLab-p4-reports`）已移除；本地分支只剩 `main`；没有 Docker 容器和卷；没有相关的计划任务 |
| 云端 | 没有排着的定时任务或看门狗；本机 Remote Control 会话已全部停止 |

### 远端分支

除 `main` 外还有 24 个分支，内容都已在 `main` 或已存档，删不删不影响恢复：

- 已合并的代码分支：`p1-acceptance`、`p1-wrapup`、`p1a-ledger`、`p1b-1`～`p1b-8`、`p1c-1`～`p1c-4`、`p2`、`p3`、`p4`。`p1b-*`、`p1c-1`～`p1c-3` 的末端是叠加 PR 的合并提交，内容经 `p1c-4-detail-page` 进了 `main`。
- 任务与报告分支：`p2-tasks`、`p2-reports`、`p3-tasks`、`p3-reports`、`p4-tasks`、`p4-reports`。它们只有任务说明、Codex 答复和执行报告，没有进 `main`，已复制到项目共享资料（不在本仓库）。

## 2. 遗留问题

按要用到的阶段排列。

| # | 问题 | 什么时候做 | 需要什么 |
|---|---|---|---|
| 1 | 提现相关的冲突证据（`PAYOUT_STATE_CONFLICT`）怎么纠错，P4 有意留空 | P5 设计时定 | ADR-0011 再补一节 |
| 2 | 撤销（`VOIDED`）后处理方又把这笔支付结算进批次：批次 `HELD(PAYMENT_STATE_CONFLICT)`，目前只能人工处理 | P5 或之后，看是否需要 | 设计决定 |
| 3 | Stripe 真实测试账号实测：自动 payout 是否走到 `reconciliation_completed`、退款与 payout 的真实行为（stripe-integration.md 第 7 节的假设） | P6 | 本机 `.env` 里的 `STRIPE_API_KEY`（测试模式），只能在本机跑 |
| 4 | 本地只读 MCP（DBHub）连不上 | 需要用 AI 直接查库时 | 先 `docker compose up -d`，再设置环境变量 `LEDGERLAB_READONLY_PASSWORD` 并重启客户端 |
| 5 | 只读数据源没有连接池；`core.v_payment_delivery` 每次查询解析事件 JSON 找 charge 编号 | P7 性能基线前 | 改连接池、把 charge 编号存成列 |
| 6 | PIT 存活变异：审计事务的隔离级别没被测试观察到 | 有并发写入的模型动作时 | 补“审计期间有人记账也不报假差异”测试 |
| 7 | 公开仓库前：`docs/brief.md` 含个人内容，要移出仓库并清理历史；之后按 [github-setup.md](../github-setup.md) 开分支保护 | P7 公开前 | 用户决定公开时间 |

## 3. P5 怎么重新开工

P5 = 提现：双门槛（可用款和 `free_cash`）、模拟银行出站命令、结果未知恢复、失败与退回、提现侧对账。退出条件是 AC-1.4e 和 AC-7（[phases.md](../design/phases.md)）。设计已在 P0 写好：[money-model.md](../design/money-model.md) 第 4.2 节和样例 A 第 9～11 步、样例 B、C、G；[ADR-0006](../adr/0006-payout-unknown-outcome.md)、[ADR-0008](../adr/0008-outbound-command-table.md)；[state-machines.md](../design/state-machines.md) 第 3 节提现状态机。

1. **准备环境**：本机 JDK 25、Node 24、Docker Desktop 运行中。`git checkout main && git pull --ff-only`，跑一遍 `./mvnw verify` 和 `sh scripts/smoke-p4.sh`，确认暂停期间没坏。
2. **先补设计**：遗留问题第 1 条（提现冲突证据的纠错），以及 P5 用到的银行出账匹配规则（现在 `DEBIT` 行除精确退还待查收款外一律不记账，见 money-model.md 第 3 节说明）。改进 ADR/AC 后再拆任务。
3. **执行方式照 P3/P4**：三个分支 `p5`（代码，从 `main` 建）、`p5-tasks`（`RUNNER.md`、`QUEUE.txt`、`tasks/_common.md`、`tasks/Txx`）、`p5-reports`（本机 worktree `..\LedgerLab-p5-reports`）。任务模板可从上一阶段的 tasks 分支或存档复制。分工和提速规则见 [AGENTS.md](../../AGENTS.md)“分工”：Codex 只跑编译、Spotless 和相关测试，全量 verify 由本机执行者跑一次。
4. **收尾**：写 `docs/handoff/p5.md` 验收清单，开 `p5` → `main` 一个 PR。

模拟器已有的、P5 会用到的部分：独立银行（模拟器 V4）、时钟推进接口 `POST /sim/clock/advance`。
