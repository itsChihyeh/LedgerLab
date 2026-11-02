# T04 沙箱栈脚本、不变量接口、AC-8.4 真实 webhook 进入

## 背景
stripe-integration.md 第 9.1、9.7（不变量）、9.8 节；`compose.yaml`、`compose.smoke-p5.yaml`、`scripts/smoke-common.sh`（风格）；T01 结论（CLI 是否能用 `--api-key`、`STRIPE_CLI_USE_LOGIN`）；T02、T03 的接口。AC-8.4。

## 范围
1. `compose.sandbox.yaml`（叠加在 `compose.yaml` 上）：profile `sandbox`，项目名由脚本固定为 `ledgerlab-sandbox`；持久卷（名字和冒烟的不同）；端口只绑 127.0.0.1：app 18080、simulator 18081、postgres 15432。app：`STRIPE_API_BASE_URL=http://simulator:8081/relay/stripe-api`、`STRIPE_API_KEY`、`STRIPE_WEBHOOK_SECRET` 从脚本导出的环境变量传入（不写进任何文件，`.sandbox/secrets.env` 除外）；simulator：`SPRING_PROFILES_ACTIVE=sandbox`、`SIM_RELAY_TARGET_URL=http://app:8080/webhooks/stripe`、`sim.relay.record-bodies=true`、run-id/window-start 来自 `.sandbox/run.json`。数据库密码等沿用 `.env` 的本地值（脚本按 env.sh 同样的逐行方式只导出需要的键）。
2. `scripts/sandbox/`：
   - `up.sh`：加载 env.sh；首次运行生成 `.sandbox/run.json`（run_id、window_start；已有就沿用，`--new-run` 才换）。window_start 默认 `2026-10-20T00:00:00Z`（早于账号注册），这样 T01 探测和账号里任何更早的测试支付都会被 9.2 补投进账本，不会让包含它们的 payout 批次因“未知支付”而 HELD；可用 `--window-start` 覆盖；用 `listen --print-secret` 取 whsec 写 `.sandbox/secrets.env`（权限尽量收紧）；构建 jar、`docker compose ... up -d --build --wait`；后台起 `stripe listen --forward-to http://127.0.0.1:18081/relay/stripe --events <列表>`（第 2 节订阅的事件＋`charge.dispute.created`），pid/日志放 `.sandbox/`，已在运行就不重复起。幂等，可反复执行。
   - `down.sh`（停 listen 和容器，**保留卷**；只有 `--purge` 才删卷，并要求再输入 run_id 确认）、`status.sh`（容器、listen、run.json、app/simulator 健康）。
   - `traffic.sh --count N --card X --seed S`：调 `/sim/sandbox/payments`。
   - `accept.sh [--only ac-8.4|ac-8.5|…]`：本任务实现 ac-8.4 部分：在 `traffic` 之后等待（有界轮询，最长 5 分钟）本次生成的每个 ch_ 都在 `GET /api/payments/{ref}` 里 `CAPTURED`；对应事件在 `GET /api/events/{evt}` 可查且签名验证通过（inbox 有记录即说明通过了验签）；然后对 relay 入口发一个篡改过正文的真实事件副本（取一条 relay 录制的原文改一个字节，原签名头）和一个缺签名头的请求，app 返回 401（经 relay 转发时看 relay 日志里的 401，或直接打 app 端口）。最后调不变量接口，违反数必须为 0。输出 PASS/FAIL 摘要到 `target/sandbox/accept-<时间>.json`。
3. 主应用 `investigation`：只读 `GET /api/audit/invariants` 返回 `{checkedAt, violations:[{invariant, detail}]}`，调用 `LedgerAudit.check()`；如果 reconciliation／refunds／payouts 已有公开的不变量检查方法，一并调用并在最后输出列出；没有就只用 LedgerAudit，不新写检查逻辑。readonly 能调（照现有只读接口的鉴权方式）。加 MockMvc＋Testcontainers 测试：干净库 0 条；人为制造的违规（用测试里已有的方式，例如直接以 migrator 写一条不平衡余额）能报出。
4. README 的命令列表加“真实沙箱运行”几行（不改其他段落）；stripe-integration 第 9.8 节补实现备注。

## 执行者和 Codex 都要实际跑（这是真实 Stripe）
`sh scripts/sandbox/up.sh` → `sh scripts/sandbox/traffic.sh --count 20 --card pm_card_bypassPending --seed 1` → `sh scripts/sandbox/accept.sh --only ac-8.4`。AC-8.4 要求 20 笔都 CAPTURED。结束后**保持栈运行**。如果 T01 结论是 CLI 需要用户登录且用户还没登录，脚本应明确报“需要用户执行 stripe login”并以 78 退出，此时只提交代码、在问题里写明。

## 登录账号核对（2026-11-02 补）
用户 20:56Z 在 `stripe login` 授权页选了“测试企业 沙盒”，执行者 2026-11-02 已用 `events retrieve` 核对是同一账号（p6-reports 的 LOGIN.md）。`up.sh` 仍要每次起 listen 前核对一次，判据按 LOGIN.md 的实测：用 CLI（登录模式）`events retrieve evt_3UXQCaF5hIinUF490brSX7aS`，解析输出 JSON——读到该事件即同一账号；`error.code == resource_missing` 则不是同一账号，以 78 退出并提示“请用户重新 stripe login，改选另一个沙盒环境（测试模式）”。**不要靠 CLI 退出码判断**（出错也是 0），也不要用 `resend` 做核对（没有 listen 时必报 for_stripecli）。listen 起来以后再 `events resend` 这条事件，观察本地 relay 是否收到，完成第 7 节第 11 条，结论回写；resend 的成败同样解析输出判断。

## 可改范围
`compose.sandbox.yaml`、`scripts/sandbox/**`、`.gitignore`、`src/main/java/dev/ledgerlab/investigation/**` 及其测试、README 命令列表、`docs/design/stripe-integration.md`。

## 完成标准
按 `_common.md` 第 5 条（主应用相关测试＋ModularityTests）；`python -m py_compile scripts/sandbox/*.py`；上面的真实运行通过（或按 78 的情况说明）。本地提交（`Task: T04`）。

## 执行者验证
全量 verify 后，亲自跑 `sh scripts/sandbox/up.sh && sh scripts/sandbox/accept.sh --only ac-8.4`（不要再跑 traffic，避免重复 20 笔；accept 应能找到本 run 已生成的支付），报告附 accept 的摘要 JSON（不含密钥）。
