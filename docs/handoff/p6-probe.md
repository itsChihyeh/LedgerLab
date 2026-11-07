# P6 T01 首次 Stripe 探测（2026-11-02）

状态：**按 2026-11-02 审查后的验收口径完成 T01**。两笔支付成功，脚本连续两次退出 0，CLI 未登录按约定跳过。未创建退款、未改账号设置或 Java／依赖。本任务为 AC-8.3～8.6、8.8～8.10 提供前置探测证据，不替代后续端到端验收；密钥防护对应 AC-8.1、8.2。

## 执行与结果

Windows Git Bash 执行 `sh scripts/sandbox/probe.sh`，经 env.sh 仅加载根 `.env` 的 STRIPE_API_KEY，不 source 文件、不输出密钥。首次成功运行于 **2026-11-02 18:43:28 UTC**，按执行者验证要求于 **18:43:56 UTC** 再跑一次，两次均退出 **0**。第二次 GET 已保存对象，charge 编号、金额、手续费及余额完全一致，没有新增支付。共成功创建 **2 笔 PaymentIntent、0 笔退款**。

| 项目／设计第 7 节编号 | 实测结论 |
|---|---|
| 密钥／9 | balance、payouts、events、charge、balance transaction 读取及 PaymentIntent 创建确认成功；balance、PaymentIntent、charge 均为 livemode=false |
| 初始与最终余额／1、8 | 初始 CAD available=0、pending=0；支付后 available=1158、pending=9680，重跑保持相同（整数分） |
| 美国 bypass 卡／2 | pm_card_bypassPending，CAD 1234 分，charge 类型 card、国家 US；fee=76、net=1158、status=available、available_on=1793645009，与 charge.created 相同，确实立即可用。国内公式为 66 分，差额 +10 分；不把单笔差额推导为完整国际卡费率 |
| 加拿大国内卡／3 | 官方表对应 pm_card_ca，CAD 10000 分，charge 类型 card、国家 CA；fee=320、net=9680、status=pending、available_on=1794182400。手续费等于 30 + round(2.9%)，不改公式、不加容差；单笔金额不能验证所有舍入边界 |
| 退款／4 | 0 笔；待 T07 经主应用发起退款时观察余额不足 pending，避免未来批次出现账本未知退款 |
| 历史 payout／1、8 | GET /v1/payouts?limit=10 为 0 条、has_more=false；自动 payout、按 payout 过滤明细、reconciliation_completed 均待 T07 观察 |
| 事件版本／12 | 初始 account.updated 与支付后最近五条事件均为 2026-09-30.endive；含 charge.succeeded、charge.updated、payment_intent.created、payment_intent.succeeded。listen 投递版本待用户登录后由 T04 观察 |
| CLI 签名密钥／10 | 上一轮受限密钥不能取得（PERMISSION_ERROR）。本轮默认登录模式，listen 不带 API key，结果 LOGIN_REQUIRED；输出“需要用户 stripe login”后跳过，不影响其余成功项的退出 0 |
| CLI 重投／11 | 已找到 bypass 的 charge.succeeded；没有登录会话，未执行 resend／60 秒观察。待用户登录后由 T04 观察，不能判定本地 listener 是否能收到重投 |
| 失败 payout 银行／5 | 仅文档：加拿大 routing 11000-000、测试账号 000111111113 → account_closed；000111111116 → no_account。未配置、未验证本账号 |
| webhook 重试／6 | 仅文档：sandbox 几小时内重试三次；live 指数退避最长三天。未注入失败、未测实际间隔 |
| 速率上限／7 | 仅文档：sandbox 全局 25 请求/秒，普通单端点默认 25 请求/秒，另有专门限额与并发限制；未压测 |

### 可追溯对象

| 对象 | 编号 |
|---|---|
| bypass charge | `ch_3UXQCaF5hIinUF490AcFsuFL` |
| 加拿大 charge | `ch_3UXQCeF5hIinUF491bBrSwJa` |
| bypass charge.succeeded | `evt_3UXQCaF5hIinUF490brSX7aS` |
| 加拿大 charge.succeeded | `evt_3UXQCeF5hIinUF4912JhIFd6` |
| 加拿大 charge.updated | `evt_3UXQCeF5hIinUF491DEUxsJF` |
| 加拿大 payment_intent.succeeded | `evt_3UXQCeF5hIinUF491CuNA7cX` |
| 加拿大 payment_intent.created | `evt_3UXQCeF5hIinUF491P4LgaSb` |
| bypass charge.updated | `evt_3UXQCaF5hIinUF490gJgvwrZ` |

T04 补投窗口应覆盖 **2026-11-02 18:43 UTC**，以便这些测试支付在其所属 payout 到达前进入主应用。不伪造签名或绕过 webhook 入口；补不回来的按 9.2 写入后续报告。

## 请求契约与幂等恢复

- 按云端审查，移除 payment_method_types[]，改用 automatic_payment_methods[enabled]=true、automatic_payment_methods[allow_redirects]=never；仍然 CAD、confirm=true、测试 PaymentMethod、metadata run_id=probe。成功后断言 charge 的 payment_method_details.type 为 card。第 9.3 节已同步，供 T03 使用。
- 上一轮旧参数遭 HTTP 400 拒绝，没有成功对象。18:42 UTC 用新参数复用旧编号 1，返回 **HTTP_400 type=idempotency_error**，此时 balance 仍为零。未保存或输出 error.message／错误原文。旧响应的 type/code/param 未留存，不追溯编造。
- 小的实现取舍：固定编号 1 退役，保留本地旧尝试；修订 bypass 请求使用 **probe-20261102-4**，尚未用过的加拿大请求仍用 **probe-20261102-2**；CLI resend 保留 **probe-20261102-3**。符合任务固定 probe-20261102-<n> 格式。不会随机换键或每次重跑换键；脚本拒绝用编号 1 再发送支付。
- `.sandbox/probe-state.json` 只存尝试时间和成功对象编号。成功后重跑只 GET 原对象；结果不确定且首次尝试超过 23 小时则停止并要求核查，避免把至少 24 小时保留的服务端幂等键当作永久保证。不要删除状态来绕过保护。
- API 固定 Stripe-Version: 2026-09-30.endive，不跟随重定向；金额按有符号 64 位整数验证，公式用精确整数运算，Payments 使用注入的 UTC 时钟。
- 错误只记录 HTTP 状态和约束后的 error.type、error.code、error.param、error.decline_code；不记录 message。输出字段白名单加二次脱敏。CLI 原始 stdout/stderr 仅在内存中检查，不落盘或透传。

## CLI 安装与登录

上一轮已安装固定版本 **1.55.6**，官方 [release v1.55.6](https://github.com/stripe/stripe-cli/releases/tag/v1.55.6)。本轮重新读取官方 checksums 核验 zip，并确认已安装 exe 与 zip 内文件一致；沿用原安装，没有再次安装或改系统 PATH。

- zip：stripe_1.55.6_windows_x86_64.zip。
- 同一 release 校验文件：stripe-windows-checksums.txt。
- SHA-256：d307b986e14f9bafd696d889f80191def044678e44174d7c5b742e7c7b3c9ba7。
- 安装目录：`%LOCALAPPDATA%\ledgerlab-tools\stripe-cli\1.55.6\`。
- env.sh 优先显式 STRIPE_CLI，其次 PATH，最后本地目录按 sort -V 取最新版本；不改 PATH，不装包管理器。
- 默认 STRIPE_CLI_USE_LOGIN=1，listen/resend 不带 --api-key，且移除 CLI 子进程的 STRIPE_API_KEY；REST 保持原受限测试密钥。CLI 以非交互 stdin 运行，不触发自动登录（已核对 [1.55.6 官方实现](https://github.com/stripe/stripe-cli/blob/v1.55.6/pkg/cmd/root.go)）。

需要用户本人在 Windows 命令提示符执行一次：

```bat
"%LOCALAPPDATA%\ledgerlab-tools\stripe-cli\1.55.6\stripe.exe" login
```

PowerShell 等价命令：

```powershell
& "$env:LOCALAPPDATA\ledgerlab-tools\stripe-cli\1.55.6\stripe.exe" login
```

不等待登录、不尝试代登录。登录后 T04 经 env.sh 运行 listen 探测并重跑 `sh scripts/sandbox/probe.sh`，补第 11、12 条。此为后续观察项，不阻塞 T01。

## 官方资料

- [Testing — international cards](https://docs.stripe.com/testing#international-cards)：加拿大 PaymentMethod。
- [Create a PaymentIntent](https://docs.stripe.com/api/payment_intents/create)：请求参数；本任务始终固定项目版本。
- [Testing Stripe Connect — payouts](https://docs.stripe.com/connect/testing#payouts)：官方 Connect 测试银行表，不等同于本账号已验证能配置。
- [Webhook automatic retries](https://docs.stripe.com/webhooks#automatic-retries)、[manual retries](https://docs.stripe.com/webhooks#manual-retries)：后者不能证明本地 listener 能收到 resend。
- [Rate limits](https://docs.stripe.com/rate-limits)、[Idempotent requests](https://docs.stripe.com/api/idempotent_requests)。

本任务仅用标准库 HTTP 与 CLI，没有使用 Spring／Stripe SDK API；未用 Context7，核对的是 Stripe 官方 HTTP、CLI 与测试文档。

## 文件与验证

- 文件：.gitignore、scripts/sandbox/env.sh、probe.sh、probe.py、test_probe.py、docs/design/stripe-integration.md、本记录。设计回写第 7、9.3、9.9 节，并在 9.2 补充默认登录模式实现备注；新增差异包括参数、CLI 权限、国际卡手续费和资金可用时间。
- env.sh 支持 CRLF、末行无换行、成对引号；只加载唯一一条 STRIPE_API_KEY，拒绝缺失／重复／live／非法字符，退出 3；不执行其他配置或命令替换、不输出密钥。加载前关闭 tracing。
- `python -m unittest discover -s scripts/sandbox -p 'test_*.py' -v`：**EnvTests、ProbeTests 两个类，19 项全绿**。覆盖安全解析、CLI 选择与登录跳过、脱敏／安全错误字段、整数金额／live 拒绝、固定写入范围／版本／幂等键、无重复写入与跨天恢复、card 类型断言及事件分页；最终测试不联网、不读真实 .env。先跑失败用例再改实现。
- `sh scripts/sandbox/probe.sh`：按上述时间连续两次退出 0，结果一致。
- `python -m py_compile scripts/sandbox/*.py`（Git Bash 展开通配符）、`sh -n scripts/sandbox/env.sh scripts/sandbox/probe.sh`、`git diff --check`：通过。
- 按审查口径扫描本次新增／改动行：无密钥／账号号匹配；既有 18 个匹配文件为合成样例，保持不动。gitleaks 及本地提交 hooks 通过。
- 不涉及 Java、simulator 或 frontend；按 T01 明确豁免，不跑 Maven／全量 verify。

## 未完成部分与问题

T01 实现无未完成项。用户本人 CLI 登录仍待办；第 11 条及第 12 条 listen 投递版本交给 T04，第 1、4、8 条交给 T07。T02、T03 不依赖登录，可按计划继续。本任务只本地提交，不 push、不开 PR。
