# P6 执行已停止（2026-11-02 22:33，本机时间，UTC-5）

用户的 Codex 会员到期，要求停掉 P6 执行。这是暂停，不是完成。

## 状态

- `p6` 最新提交：`948401e`，与 `origin/p6` 一致，工作区干净。
- `p6-tasks` 最新提交：`c0e996e`。
- **T01–T06：完成**，都已 push，各自的报告在本分支。最后一次全量测试（T06）：主应用 1809 个、模拟器 254 个，失败 0。
- **T07、T08：未开始**。P6 没有完成。AC-8.6、AC-8.8、AC-8.9 还没有真实运行，AC-8.3 只做了 S1 场景。
- `answers/T05-review.md` 第 2 条（`catchup-*.json` 加 `recovered_event_ids`）和第 1 条（验收清单写明 AC-8.5 首次 FAIL）留给 T08，都还没做。

## 最后一次 check-payout（22:33）

- 退出 75，等待中。
- payout 条数：0。
- 余额（CAD 分，`livemode=false`）：可用 306651，待结算 9680。

## 已停止的东西

- 队列循环和 payout 轮询：已停，确认没有残留。
- 沙箱：`sh scripts/sandbox/down.sh` 退出 0，没有 `ledgerlab-sandbox` 容器，没有 stripe 进程。
- `.claude\codex.lock`：已删。
- 没有任务用的 Codex 进程在跑。本机还有两个 `codex.exe`（10-30 启动的 `app-server`、`exec-server`），是用户的 Codex 桌面应用，我没动。

## 保留的东西

- Docker 卷 `ledgerlab-sandbox_sandbox-pgdata`（本地账本数据）。
- 主仓库下的 `.sandbox/`（已 gitignore）：`run.json`（run `sbx_20261102T224453022706Z`）、`secrets.env`（本地数据库密码和签名密钥）等。不要删，密码变了持久卷里的角色就登录不上了。
- `target/sandbox/`：验收和 catchup 的结果文件。`mvnw clean` 会删掉它们，T08 的报告要用，要留的话先备份。
- Stripe CLI 1.55.6：`%LOCALAPPDATA%\ledgerlab-tools\stripe-cli\`，登录状态保留。
- Stripe 里的自动 payout 不受影响，照常按 Stripe 的时间走。

## 恢复

```
sh scripts/sandbox/up.sh
sh scripts/sandbox/catchup.sh
sh scripts/sandbox/check-payout.sh
```

注意：

1. 停机期间的事件靠 catchup 补投。**Stripe Events API 只保留 30 天**，从停机算起超过 30 天 catchup 会报“不完整”，所以要在 2026-12-02 前恢复一次。
2. `up.sh` 会用 `listen --print-secret` 重新取签名密钥，也会核对 CLI 登录账号。如果 CLI 登录过期，它以 78 退出，需要用户本人重新 `stripe login`。
3. 这里的步骤只恢复沙箱。T07、T08 原本由 Codex 实现；没有 Codex 的话，要先决定由谁实现，再按 RUNNER 继续。`..\LedgerLab-p6-reports` worktree 已清掉，恢复时要重建（从 `origin/p6-reports`）。
