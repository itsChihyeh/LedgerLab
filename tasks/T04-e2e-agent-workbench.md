# P9-T04 端到端场景 `agent-workbench`

## 背景
读 `docs/design/agent-workbench-and-mcp.md` 第 3 节和第 3.2 节实现备注，AC-11.3～AC-11.6，ADR-0011 第 2 节③。看 `frontend/e2e/run.mjs`、`frontend/playwright.config.ts`、`frontend/e2e/helpers.ts`、`frontend/e2e/recon.spec.ts`、`frontend/e2e/corrections.spec.ts`；测试后端 `src/test/java/dev/ledgerlab/TestLedgerLabApplication.java` 与 `src/test/resources/scenarios/`（`sample-a-1-3`、`ac-5-3` 是怎么组织的，`manifest.json` 写了什么）；银行流水导入 `POST /api/bank-statements`（`src/main/java/dev/ledgerlab/settlement/BankStatementController.java`、`BankCsv.java`）；P8 规则基线怎样判定 `BANK_SPLIT_DEPOSIT` 与 `PROCESSOR_FEE_DIFFERS`（`docs/design/investigation-agent.md` 第 7 节实现备注、`agent/src/ledgerlab_agent/baseline.py`）。面板与调查服务已由 P9-T01、T03 完成。

## 范围
1. **测试数据**：让测试后端在新场景名 `agent-workbench` 下有（至少）：
   - 一个已 `CONFIRMED`、在等银行入账的批次，以及一份银行流水，把它拆成两笔同 reference、合计等于批次净额的入账（`DEPOSIT_AMOUNT_MISMATCH` 两条、流水为 `UNAPPLIED`），P8 规则基线能把它判成 `BANK_SPLIT_DEPOSIT`；
   - 一条 `FEE_MISMATCH`，基线能判成 `PROCESSOR_FEE_DIFFERS`。
   做法自选，按偏好：(a) 复用 `ac-5-3` 场景（它已有一条 FEE_MISMATCH），在 E2E 的 `beforeAll` 里用已有接口（导入银行流水 CSV、`POST /api/recon/runs` 之类）补出拆分到账；(b) 新建 `src/test/resources/scenarios/agent-workbench/`。只用合成数据。要改 `TestLedgerLabApplication` 的场景白名单可以改；**不许改 `src/main/**`**；如果必须改主应用才能造出这两条差异，停下来问。
2. **启动调查服务**：`playwright.config.ts` 在这个场景下多一个 `webServer`：`python ../agent/ledgerlab-agent-service.py --base-url http://127.0.0.1:8090 --model stub --port <固定的 E2E 端口，如 8098> --store <test-results 下的临时文件>`，就绪地址 `/agent/health`；Vite 用 `VITE_AGENT_PORT` 指到它。其他场景不启动调查服务、行为不变。`run.mjs` 的场景列表加入 `agent-workbench`（默认运行）。Windows 上 `python` 命令名不同时以本机实际为准，并在实现备注里写明。
3. **`frontend/e2e/agent.spec.ts`**：
   - `ac_11_6 …`：打开拆分到账的一条差异 → 点“让 Agent 调查” → 看到 `BANK_SPLIT_DEPOSIT`、替身提示、引用；点一条 `bank_line` 引用能到银行流水页，返回；点“采纳” → **断言点“采纳”没有产生任何新的非 GET 请求**（`page.on("request")` 记录全部非 GET 请求；到这一刻应只有 1 个 `POST /agent/investigations`）；填操作人、提交 → 恰好一个 `POST /api/corrections`；页面刷新后差异 `RESOLVED`，链到纠错，纠错详情页的备注以 `采纳调查 inv_` 开头，同一批次的另一条拆分差异也已 `RESOLVED`（ADR-0011 ③自动关闭）。
   - 手续费差异：调查 → `PROCESSOR_FEE_DIFFERS` → 采纳 → 提交 → 恰好一个 `POST /api/recon/issues/{id}/accept`，差异 `ACCEPTED`，状态历史的说明以 `采纳调查 inv_` 开头。
   - 全程统计：主应用收到的写请求只有上面两次（AC-11.6）。
   - `ac_11_5 …`：用 `page.route("**/agent/**", r => r.abort())` 模拟调查服务不在：面板显示“调查助手未启动”，差异详情的其余部分（金额比较、关联证据、“接受”表单）照常显示。
   - 截图存 `test.info().outputPath(...)`（与现有 spec 一样，不提交截图）。
4. 回写设计第 3 节与第 6 节实现备注（场景怎么造的、端口）。

## 可改范围
`frontend/e2e/agent.spec.ts`（新建）、`frontend/e2e/run.mjs`、`frontend/e2e/helpers.ts`、`frontend/playwright.config.ts`、`src/test/java/dev/ledgerlab/TestLedgerLabApplication.java`、`src/test/resources/scenarios/agent-workbench/**`（若选 b）、`docs/design/agent-workbench-and-mcp.md`。前端 `src/` 只允许修 E2E 暴露出的 bug（先在 `agent.test.tsx` 里加一个复现的失败测试再修，最后输出列出）。

## 完成标准
- `cd frontend && npm run check` 全绿；`npm run test:e2e -- --scenario=agent-workbench` 通过（需要 Docker；先确认 8090、5173 和你选的调查服务端口空闲，被占用就停下来报告）；再跑一次 `npm run test:e2e -- --scenario=ac-5-3` 和 `--scenario=correction-e` 证明旧场景没被带坏。
- 若改了 Java：`./mvnw -B spotless:apply`、`./mvnw -B test-compile spotless:check`。
- 提交带 `Task: P9-T04`。最后输出写每个场景的测试数与结果、用的是 (a) 还是 (b)。
