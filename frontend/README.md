# P2 页面与浏览器验收

从 `frontend/` 运行：

```sh
npm run check
npm run build
npm run test:e2e
```

需要 JDK 25、Node 24、Docker 和已安装的 Playwright Chromium。后端由 Maven `spring-boot:test-run` 启动，使用 Testcontainers 的临时 PostgreSQL。测试不读取 `.env`，不连接真实 Stripe，也不直接写数据库生成种子。

`test:e2e` 顺序运行两个独立场景，每轮结束自动停止后端和 Vite：

| 场景                          | 浏览器验收                                                                                                 |
| ----------------------------- | ---------------------------------------------------------------------------------------------------------- |
| `sample-a-1-3`                | P1 三条回归；AC-1.11 的 S3 金额、三类余额、批次与银行链接、9 条 posting；AC-2.16 的 B1 新事件号重放        |
| `ac-5-3`，截至 `first` 检查点 | 独立真值断言恰好七条差异；AC-5.15 的金额、发现与处置历史、逐条证据跳转、带原因接受及筛选；未知证据和空结果 |

场景证据复用 `src/test/resources/scenarios/` 的 T12 导出文件，原始事件经签名 webhook 进入，处理方响应经本地 HTTP 回放，银行 CSV 经导入接口进入，差异经对账接口产生。排空 worker 使用受控调用，签名时间取后端注入的 Clock。

报告分别写到 `playwright-report/sample-a-1-3/`、`playwright-report/ac-5-3/`；失败 trace 写到对应的 `test-results/` 子目录。截图位于忽略的 `e2e-artifacts/`。

单独调试工作台：在启动 Playwright 的环境中设置 `LEDGERLAB_E2E_SCENARIO=ac-5-3`，再运行 `node node_modules/@playwright/test/cli.js test`。未设置时运行 S3 与 P1。两轮使用相同的 8080（后端）、8090（仅 loopback 测试控制）、5173（Vite）端口，不能同时启动。

`e2e/helpers.ts` 的 `replayB1Confirmation(request)` 是 AC-2.16 演示辅助函数：加载原 B1 确认，只换 `event_id`，准备原处理方响应，按测试时钟重签并投递，最后受控处理。测试比较重放前后的完整 posting 集合、账户余额与账本序号，并检查日志新增“重复”。

路由入口为 `#/`、`#/batches/{id}`、`#/recon`、`#/payments/{ref}`；P1 `/payments/{ref}` 深链仍可用。工作台的筛选条件在 hash 查询参数中。金额显示沿用 `money.ts`，未确认金额不当作零。AC-1.11 负可用的真实入口 E2E 留 P3 退款阶段，不通过 SQL 造负余额。
