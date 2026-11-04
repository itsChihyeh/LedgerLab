# P2 页面与浏览器验收

从 `frontend/` 运行：

```sh
npm run check
npm run build
npm run test:e2e
```

需要 JDK 25、Node 24、Docker 和已安装的 Playwright Chromium。后端由 Maven `spring-boot:test-run` 启动，使用 Testcontainers 的临时 PostgreSQL。测试不读取 `.env`，不连接真实 Stripe，也不直接写数据库生成种子。

`test:e2e` 顺序运行独立场景，每轮结束自动停止后端和 Vite：

| 场景                                                                 | 浏览器验收                                                                                                 |
| -------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| `sample-a-1-3`                                                       | P1 三条回归；AC-1.11 的 S3 金额、三类余额、批次与银行链接、9 条 posting；AC-2.16 的 B1 新事件号重放        |
| `ac-5-3`，截至 `first` 检查点                                        | 独立真值断言恰好七条差异；AC-5.15 的金额、发现与处置历史、逐条证据跳转、带原因接受及筛选；未知证据和空结果 |
| `refund-concurrent-6000` / `refund-concurrent-5000` / `refund-retry` | 退款并发、等待资金、商户欠款与原键重试                                                                     |
| `correction-e`                                                       | 样例 E 的冲销、重记、状态与证据链接                                                                        |
| `payout-recovery`                                                    | AC-7.19：原键重试、银行丢回执、UNKNOWN → ACCEPTED → PAID、单笔付款与余额                                   |

场景证据复用 `src/test/resources/scenarios/` 的 T12 导出文件，原始事件经签名 webhook 进入，处理方响应经本地 HTTP 回放，银行 CSV 经导入接口进入，差异经对账接口产生。排空 worker 使用受控调用，签名时间取后端注入的 Clock。

报告分别写到 `playwright-report/sample-a-1-3/`、`playwright-report/ac-5-3/`；失败 trace 写到对应的 `test-results/` 子目录。截图位于忽略的 `e2e-artifacts/`。

单独调试工作台：在启动 Playwright 的环境中设置 `LEDGERLAB_E2E_SCENARIO=ac-5-3`，再运行 `node node_modules/@playwright/test/cli.js test`。未设置时运行 S3 与 P1。两轮使用相同的 8080（后端）、8090（仅 loopback 测试控制）、5173（Vite）端口，不能同时启动。

`e2e/helpers.ts` 的 `replayB1Confirmation(request)` 是 AC-2.16 演示辅助函数：加载原 B1 确认，只换 `event_id`，准备原处理方响应，按测试时钟重签并投递，最后受控处理。测试比较重放前后的完整 posting 集合、账户余额与账本序号，并检查日志新增“重复”。

路由入口为 `#/`、`#/batches/{id}`、`#/recon`、`#/payments/{ref}`；P1 `/payments/{ref}` 深链仍可用。工作台的筛选条件在 hash 查询参数中。金额显示沿用 `money.ts`，未确认金额不当作零。AC-1.11 负可用的真实入口 E2E 留 P3 退款阶段，不通过 SQL 造负余额。

## P5 提现演示（T10 / AC-7.19）

`#/payouts` 提供分页列表，`#/payouts/{mpo_id}` 显示时间线、脱敏尝试、出账/退回流水、分录及差异链接。总览展示提现在途、free_cash 与零现金提示。纠错详情支持 `CONFIRM_FAILED_PAYOUT_PAID` 并链接原提现。

提现表单只在启动或构建前设置 `VITE_SIMULATOR_MODE=true` 时展示，默认只读。此开关只是界面模式，不是 API 授权机制。不要写 `.env`；PowerShell 可用 `$env:VITE_SIMULATOR_MODE='true'; npm run dev`。金额按 CAD 元输入两位小数，发送整数分；业务编号永久标识同一笔提现；网络失败后锁定原请求内容并提供原键重试。

可重复演示脚本就是 `e2e/payouts.spec.ts`，单独运行：

```powershell
$env:LEDGERLAB_E2E_SCENARIO='payout-recovery'
$env:VITE_SIMULATOR_MODE='true'
node node_modules/@playwright/test/cli.js test
```

测试启动真实独立模拟器 JVM 和数据库（首次自动构建模拟器），通过 HTTP 为银行建立 CAD 96.80 现金；主应用复用 S3 的独立证据，银行与主应用不共享库。按下列受控步骤运行，无 sleep：
1. 给银行 `*` 计划配置 `EXECUTE_DROP_RESPONSE`，通过页面以业务编号 PO1 提现 CAD 96.80。先证明余额不足的 409，再丢弃一次已提交的浏览器响应，使用原键重试，列表仍只有一笔。
2. loopback 测试控制 `/payout-worker` 处理一次，页面显示 UNKNOWN，现金 96.80、提现在途 96.80、商户可用与 free_cash 均为 0。
3. `/advance-clock` 推进注入时钟 2 秒，再调用 worker，先查询同一请求，页面显示 ACCEPTED，两次尝试。
4. `/payout-execute` 将独立银行时钟从周五推进至周一，导入模拟器生成的银行 CSV。页面变为 PAID，显示 `bt_{mpo_id}_1` 与记账分录；四项余额均为 0。
5. 直接读取模拟器 `/sim/bank/payments`，断言只有一笔同请求号、同银行确认号、EXECUTED 的付款；金额 9680 由银行流水和账本分录断言。

上述控制接口仅存在于测试后端；正常应用不注册。单独演示使用与其他 E2E 相同的端口，不能并行启动。报告写到 `playwright-report/payout-recovery/`。
