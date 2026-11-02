# P5 开工基线（SETUP）

时间：2026-11-02 00:25–01:07（本机 -05:00）。基线提交：main `d6740b7`。

## 环境核对
- origin：`https://github.com/itsChihyeh/LedgerLab.git`，正确，未改。旧仓库保留为 remote `archive`。
- 工作区：干净。
- Docker：开始时没有运行，由我启动 Docker Desktop（29.9.1），5 秒内可用。
- Codex CLI：`codex.ps1` 可调用，版本 codex-cli 0.166.0-alpha.5.1。
- `.claude\codex.lock`：无残留。
- `.env`：基线和冒烟都没有读。smoke 只用公开的 `compose.smoke.env`。

## 基线结果（全绿）
| 命令 | 结果 | 耗时 |
|---|---|---|
| `.\mvnw.cmd -B verify` | 1556 个测试，0 失败，0 错误，0 跳过；BUILD SUCCESS | 35:52 |
| `.\mvnw.cmd -B -f simulator/pom.xml verify` | 122 个测试，0 失败；BUILD SUCCESS | 01:15 |
| `npm ci` | 成功，0 个漏洞 | 4 秒 |
| `npm run check` | 3 个文件 25 个测试通过 | 2 秒 |
| `sh scripts/smoke-p4.sh` | P3、P4 两段冒烟都通过，容器和卷已清理 | 291 秒 |

结论：暂停期间没有损坏。

## 休眠设置（只查未改）
电源方案 NIGHT：
- 接电源：不待机、不休眠。
- 用电池：闲置 25 分钟待机，不休眠。

用电池时长任务可能被待机中断，长时间运行需要接电源。

## 分支
- 主仓库已切到 `p5`（从 `origin/p5` 建，`17ed506`）。
- 报告 worktree：`..\LedgerLab-p5-reports`，分支 `p5-reports`。
