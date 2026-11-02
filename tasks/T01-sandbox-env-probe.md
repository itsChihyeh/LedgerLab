# T01 密钥加载、Stripe CLI、开户假设实测

## 背景
stripe-integration.md 第 5 节（密钥）、第 7 节（要实测的假设 1～12）、第 9.1、9.2 节。账号是加拿大测试沙盒；受限密钥 `ledgerlab-dev` 的权限见第 7 节第 9 条。本任务是 P6 第一次真正连 Stripe：先确认密钥能用、CLI 能用，再把能立即测的假设测掉，后面的任务按结论走。

## 范围
1. `scripts/sandbox/env.sh`（被其他脚本 `.` 引用）：从仓库根的 `.env` 只取 `STRIPE_API_KEY` 一行（逐行解析 `KEY=VALUE`，不 `source` 整个文件，不 echo），`export STRIPE_API_KEY`；前缀必须是 `rk_test_` 或 `sk_test_`，否则打印“STRIPE_API_KEY 缺失或不是测试模式密钥”并以 3 退出（不打印值）。另外定位 Stripe CLI：优先 `$STRIPE_CLI`，其次 PATH 里的 `stripe`，其次 `$LOCALAPPDATA/ledgerlab-tools/stripe-cli/*/stripe.exe`（取版本最新的），导出 `STRIPE_CLI`。`.gitignore` 加 `.sandbox/`。
2. **Stripe CLI**：如果本机没有，下载 GitHub `stripe/stripe-cli` 官方 release 的 Windows x86_64 zip（固定一个具体版本号），按同一 release 的 checksums 文件校验 SHA-256 后解压到 `%LOCALAPPDATA%\ledgerlab-tools\stripe-cli\<版本>\`。不改系统 PATH、不装包管理器。版本号写进 `docs/handoff/p6-probe.md`。这是开发工具，不是应用依赖。
3. `scripts/sandbox/probe.sh`＋`scripts/sandbox/probe.py`（标准库 `urllib`；`Stripe-Version: 2026-09-30.endive`；每个写请求带固定 `Idempotency-Key = probe-20261102-<n>`，重跑不会多建对象）。依次：
   a. `GET /v1/balance`：密钥可用（401/403 → 停，问题里写“需要用户检查密钥/权限”）、`livemode=false`、可用/待结算余额。
   b. 用 `pm_card_bypassPending` 建并确认一笔 CAD 1234 分的 PaymentIntent（`payment_method_types[]=card`、`confirm=true`、`metadata[ledgerlab_run_id]=probe`），取 charge、其 balance transaction 的 `fee`/`net`/`available_on`/`status`。
   c. 用加拿大国内测试卡（查 Stripe 测试卡文档里加拿大对应的 `pm_card_…`，写明出处）再建一笔 CAD 10000 分，记录 fee，与 `30 + round(2.9%)` 比较（第 7 节第 3 条）。b 的费率也记下（国际卡）。
   d. **不在本任务建退款**（探测退款的 `re_` 会进入以后的 payout，账本里没有对应退款，会让那个批次 HELD）。第 4 条（余额不足时 pending）留到 T07 经主应用发起退款时观察。
   e. `GET /v1/payouts?limit=10`、`GET /v1/events?limit=5`：记录有没有历史 payout、事件的 `api_version`（第 12 条）。
   f. CLI：`"$STRIPE_CLI" listen --api-key "$STRIPE_API_KEY" --print-secret` 能否拿到签名密钥（第 10 条；只记“能/不能＋错误类别”，不记值）。能的话，起一个本地 Python 小 HTTP 服务，`listen --forward-to` 它 60 秒，期间对 b 产生的 `charge.succeeded` 执行 `"$STRIPE_CLI" events resend <evt> --api-key …`，看本地服务是否收到、`api_version` 是什么（第 11、12 条）。
   g. 第 7 条速率上限、第 6 条重试节奏、第 5 条失败 payout 的测试银行账户：只查 Stripe 官方文档记结论和链接，不压测、不改账号设置（受限密钥也没有这个权限）。
   h. 第 1、8 条（自动 payout 与 `reconciliation_completed`）现在测不了：记录 balance 里可用余额、已有 payout 情况，写“待 T07 观察”。
   输出只写结论、对象编号（`ch_`/`re_`/`po_`/`evt_` 可以写）、金额和状态，**不写任何密钥、`acct_`、邮箱**。
4. 结论回写 `docs/design/stripe-integration.md` 第 7 节每条后面（“实测 2026-11-xx：…”）和第 9.9 节已知差异表（有差异才写）；完整记录写 `docs/handoff/p6-probe.md`。
5. 如果第 10 条“不能”：不要尝试登录。在最后输出的“问题”里写明需要用户本人在这台电脑上执行一次 `"%LOCALAPPDATA%\ledgerlab-tools\stripe-cli\<版本>\stripe.exe" login`（或 `stripe login`），并说明之后脚本改为不带 `--api-key` 调 `listen`（env.sh 里留一个开关 `STRIPE_CLI_USE_LOGIN=1`）。仍然完成其余各项后提交。

## 可改范围
`scripts/sandbox/**`、`.gitignore`、`docs/design/stripe-integration.md`（第 7、9.9 节）、`docs/handoff/p6-probe.md`。不改 Java 代码。

## 完成标准
`sh scripts/sandbox/probe.sh` 跑完（各项结论都有）；`python -m py_compile scripts/sandbox/*.py`；`git grep -nE "(rk|sk)_(test|live)_[A-Za-z0-9]|whsec_[A-Za-z0-9]|acct_[A-Za-z0-9]"` 无输出。不涉及 Java，不用跑 Maven。本地提交（`Task: T01`）。

## 执行者验证
自己再跑一次 `sh scripts/sandbox/probe.sh`（幂等键保证不会多建对象），报告里写各项结论。
