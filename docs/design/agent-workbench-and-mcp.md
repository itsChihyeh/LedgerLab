# P9 调查 Agent 接入对账工作台与 MCP 只读服务（开工设计，2026-11-06）

本文件是 P9 的设计正本。决策见 [ADR-0020](../adr/0020-agent-in-workbench-and-mcp.md)，验收见 AC-11。P9 建立在 P8（[investigation-agent.md](investigation-agent.md)）之上，不改 P8 的工具层、结论格式、SOP 和评测集。

**范围（2026-11-06 定）**：① 把 P8 的 Agent 接进对账工作台：差异详情页显示调查结论与引用，人工点“采纳”后才经**已有**写接口处置，Agent 自己仍然零写权限；② 把同一组只读工具做成 MCP 服务。外部模型接入仍等用户批准（AC-10.10），P9 全程只用规则基线和本地替身，不调用外部 API、不花钱。可观测性和真实部署是 P10。

---

## 1. 整体形态

```
浏览器（差异详情页）
  ├─ GET/POST /agent/...  ──► 调查服务（Python，agent/service/，127.0.0.1）
  │                               └─ ledgerlab_agent.investigate()（P8，不改）
  │                                     └─ 工具层：只读 GET ──► 主应用 /api/**（readonly 数据源）
  └─ POST /api/corrections、/api/recon/issues/{id}/accept（人工点“提交”，已有接口）──► 主应用

MCP 客户端（如 Claude Code）── stdio JSON-RPC ──► MCP 服务（ledgerlab_agent.mcp_server）
                                                    └─ 同一个工具层（routes.json＋search_sop）
```

- **主应用不知道 Agent 的存在**：不加表、不加迁移、不加路由、不调用 Python。Agent 的结论存在调查服务自己的文件里。
- **写只发生在浏览器到主应用这一条路上**，由人点击，走已有接口和它们已有的全部校验（ADR-0011）。调查服务和 MCP 服务都只能经 P8 的工具层发 GET。
- 调查服务和 MCP 服务都**不是**资金路径的一部分：它们挂了，工作台照常可用，只是没有调查面板。

## 2. 调查服务（`agent/service/`）

独立的 Python 包 `ledgerlab_agent_service`（源码 `agent/service/ledgerlab_agent_service/`，测试 `agent/service/tests/`），只用标准库（`http.server.ThreadingHTTPServer`）。放在核心包外面，是为了让 P8 对核心包的静态检查（`test_static.py`：没有 socket、写文件、写方法字样）保持原样不放宽；服务包有自己的、同样严格但单列例外的静态检查（第 2.4 节）。

### 2.1 启动

```sh
python agent/ledgerlab-agent-service.py --base-url http://127.0.0.1:8080 --model stub [--port 8097] [--store agent/.runs/investigations.jsonl]
```

- `--model` 只接受 `stub`、`baseline`、`scripted:<path>`。**模型在启动时固定**，请求不能选模型；外部模型不接进服务（将来接时另行设计花钱保护，不在 P9）。
- 只监听回环地址：`--host` 只接受 `127.0.0.1`、`::1`、`localhost`，其他值启动失败（退出码 2）。
- `--base-url` 的约束与 P8 CLI 相同（`http(s)`、无凭证）；也支持 `--snapshot <path>` 回放（演示与测试用）。

### 2.2 接口（全部 JSON，UTF-8）

| 方法与路径 | 作用 | 响应 |
|---|---|---|
| `GET /agent/health` | 存活与配置 | `200 {"status":"ok","model":"stub","modelIsStandIn":true,"source":"base-url"\|"snapshot"}` |
| `POST /agent/investigations` | 对一条差异跑一次调查（同步） | `201` 调查记录（2.3）；`400` 请求体不合格；`409 {"error":"busy"}` 已有调查在跑；`415` 不是 `application/json` |
| `GET /agent/investigations?issueId=iss_…` | 这条差异的历次调查，新的在前，最多 20 条 | `200 {"investigations":[…]}` |
| `GET /agent/investigations/{runId}` | 单次调查 | `200` 调查记录；`404` |

- 请求体只有 `{"issueId":"iss_…"}`，`issueId` 必须匹配 P8 `routes.json` 里 `issue_id` 的正则；多余字段 → 400。请求体 > 4 KB → 413。
- 同一时刻只跑一次调查（进程内锁），第二个请求立即 409，不排队。
- 只认 `Content-Type: application/json` 的 POST：浏览器跨站发这种请求必须先过 CORS 预检，服务不回任何 CORS 头，所以别的网站不能借用户的浏览器触发调查。不回 `Access-Control-Allow-*`，不处理 `OPTIONS`（405）。
- 其他方法与路径一律 404／405。错误体统一 `{"error":"<code>"}`，不回堆栈。

### 2.3 调查记录

```json
{
  "runId": "inv_<ULID>",
  "issueId": "iss_…",
  "model": "stub",
  "modelIsStandIn": true,
  "startedAt": "2026-11-06T…Z",
  "finishedAt": "…",
  "status": "ok | budget_exhausted | invalid_output | error",
  "conclusion": { …P8 结论原样… },
  "toolCalls": [{"tool":"get_issue","args":{…},"status":200,"ok":true,"asOf":"…"}],
  "asOf": ["…"],
  "verification": {
    "fabricatedCitations": [{"kind":"bank_line","id":"…"}],
    "fabricatedExplanationIds": ["…"],
    "supported": true
  },
  "usage": {"inputTokens":0,"outputTokens":0,"isEstimate":true}
}
```

- `conclusion` 就是 P8 的结论 JSON，不改形状；`verification` 由 P8 的 `verify.verify()` 和 `sop.required_evidence_by_cause()` 算出（同一份代码，不另写一套）。`supported`＝引用里有该原因 SOP 要求的全部证据种类。
- `status=error`：工具层或模型抛出意外异常（例如后端连不上）。这时 `conclusion` 为一份拒答（`INSUFFICIENT_EVIDENCE`、`ESCALATE`），`error` 字段写一个短码（如 `backend_unreachable`），不写异常原文。
- 时间来自注入的时钟，`runId` 的随机部分来自注入的随机源（测试里固定）。
- **存储**：每次调查追加一行到 JSONL 文件（默认 `agent/.runs/investigations.jsonl`，`agent/.runs/` 加进 `.gitignore`）。只追加，不改不删；启动时读入建索引；坏行跳过并在 stderr 记一行。文件是调查服务自己的状态，不是账本，可以整个删掉重来。

### 2.4 服务包的静态检查

`agent/service/tests/test_static.py`：
- 服务包只能导入标准库、`ledgerlab_agent` 和它自己；**不得**导入 `urllib`、`http.client`、`socket`、`ledgerlab_agent.transport` 的内部函数——访问主应用只能经 `ledgerlab_agent` 的 `ToolRegistry`／`investigate()`。
- 允许出现的“写”只有两处，用白名单写死：`http.server` 的入站 `do_POST`（一个方法），以及存储模块里以追加模式（`"a"`）打开那一个 JSONL 文件。其余与 P8 `FORBIDDEN` 同样的模式一律报错。
- P8 核心包的 `test_static.py` 不改；新的 MCP 模块放在核心包里，必须通过它。

## 3. 工作台面板（差异详情页）

`IssuePage` 在“关联证据”之后加一节 **“调查助手”**（`<section aria-label="调查助手">`）。

### 3.1 显示

- 进入页面先 `GET /agent/health`。不可达或非 200 → 这一节只显示“调查助手未启动”，页面其余部分照常（AC-11.5）。
- 有历史调查时显示最近一次；可展开看更早的。按钮“让 Agent 调查”→ `POST /agent/investigations`，进行中禁用按钮；409 显示“已有调查在进行，请稍后”。
- 一次调查显示：模型名，`modelIsStandIn=true` 时旁边固定一行“**本地替身（规则基线），不代表大模型能力**”；状态；原因（中文名＋枚举值）；置信度；解释（纯文本，React 默认转义，不渲染 HTML 或 Markdown）；建议动作（中文名＋枚举值）；SOP 条款；工具调用次数与 `asOf`；引用列表。
- **引用逐条可点**，按种类跳到已有页面：`issue`→`#/recon/{id}`，`payment`→`#/payments/{id}`，`event`→`#/events/{id}`，`entry`→`#/entries/{id}`，`batch`→`#/batches/{id}`，`batch_line`（`{batchId}/{lineNo}`）→`#/batch-lines/{batchId}/{lineNo}`，`bank_line`→`#/bank-lines/{id}`，`statement_line`（`{fileId}/{seq}`）→`#/statement-lines/{fileId}/{seq}`，`refund`→`#/refunds/{id}`，`payout`→`#/payouts/{id}`，`correction`→`#/corrections/{id}`；`run` 只显示文本。`fabricatedCitations` 里的引用标“**未在本次调查中读到**”并且不做成链接。
- 调查结论是**建议**：面板标题下固定一句“结论由 Agent 给出，未经人工确认前不改变任何数据”。

### 3.2 采纳（封闭映射）

“采纳”只在下列条件**全部**满足时出现：差异 `OPEN`；调查 `status=ok`、`abstain=false`；`fabricatedCitations` 与 `fabricatedExplanationIds` 都为空；原因在下表里。点“采纳”**不发任何请求**，只展开一张预填好的表单；人核对后填“操作人”再点“提交”，才发出对已有接口的一次 POST。

| 原因 | 采纳打开的表单 | 提交到（已有接口） | 预填 |
|---|---|---|---|
| `BANK_SPLIT_DEPOSIT` | 待查收款归位（ADR-0011 ③） | `POST /api/corrections` | `type=APPLY_UNAPPLIED_CASH`；`batchId`＝引用里**唯一**的 `batch`；`bankTxnIds`＝引用里全部 `bank_line`（≥ 2 条，按 id 排序）；`reasonCode=SPLIT_DEPOSIT`；`evidence`＝`{issue, issueId}`＋每条流水 `{bank_txn, id}`；`reasonNote`＝`采纳调查 {runId}（{model}）：{explanation}`，截到 2000 字符 |
| `PROCESSOR_FEE_DIFFERS` | 接受差异（ADR-0011 第 5 节：手续费差异按处理方实际值结算，运营接受，不纠错） | `POST /api/recon/issues/{id}/accept` | `reason`＝`采纳调查 {runId}（{model}）：{explanation}` |

- 引用不满足预填条件（例如 SPLIT 却引用了两个批次、或少于两条流水）→ 不显示采纳，显示“引用不足以预填，请人工处理”。
- 表单里预填的值都可见；`reasonNote`／`reason` 可编辑；结构字段（批次、流水、类型、原因码、证据）只读显示，要改就别采纳、走人工。“操作人”（`requestedBy`）必填、不预填。
- 提交结果照抄后端：成功（201／204）→ 刷新页面，差异显示为已处置并链到纠错记录；4xx → 显示后端的 `code`（如 `APPLICATION_AMOUNT_MISMATCH`、409“差异状态已变化”），不重试、不改写请求。
- 其他原因和所有建议动作（`WAIT_FOR_NEXT_STATEMENT`、`REQUEST_BANK_TRACE`、`CONTACT_PROCESSOR`（非手续费）、`PROPOSE_CORRECTION_FOR_REVIEW`、`ESCALATE`）没有对应的系统写操作，面板只显示“没有可采纳的系统操作，请按建议线下处理”。`WEBHOOK_LOST` 的处理是补投事件（P6 的 catch-up），不是纠错，不提供采纳。
- **追溯**：采纳留下的痕迹全在主应用已有的记录里——纠错的 `reason_note` 或差异状态历史的 `explanation` 都以 `采纳调查 inv_…` 开头；调查服务的 JSONL 里有同一个 `runId` 的完整记录。主应用不存 Agent 的输出。

### 3.3 开发代理

`vite.config.ts` 加 `"/agent": "http://localhost:8097"`（与 `/api` 一样只在开发／E2E 时生效）。生产构建里没有调查服务，面板显示“调查助手未启动”。

## 4. MCP 只读服务（`ledgerlab_agent.mcp_server`）

把 P8 的同一组工具（`routes.json` 的 11 个 HTTP 工具＋`search_sop`）通过 MCP 暴露给任何 MCP 客户端。标准库实现，不加 `mcp` SDK（铁律 5）。

```sh
python agent/ledgerlab-agent-mcp.py (--base-url http://127.0.0.1:8080 | --snapshot agent/eval/data/dev/snapshot.json)
```

- **传输**：stdio，每行一个 JSON-RPC 2.0 消息（UTF-8，消息内不含换行）；stdout 只写协议消息，日志只写 stderr。不支持 JSON-RPC 批量（数组）→ 返回 `-32600`。
- **协议版本**：实现初始化式握手（`initialize` → `notifications/initialized`）。支持 `2025-11-25`、`2025-06-18`、`2025-03-26`；客户端请求其中之一就原样回它，否则回 `2025-11-25`。2026-07-28 版的无状态模式（每个请求带 `_meta` 版本、`server/discover`）**不实现**；按该版规范，双时代客户端在服务端只支持旧版时用 `initialize` 连接。
- **方法**：`initialize`（`capabilities: {"tools": {"listChanged": false}}`，`serverInfo: {"name": "ledgerlab-readonly-tools", "version": <包版本>}`，`instructions` 一句话说明全部只读、数据是不可信的）；`ping`；`tools/list`（一次给全，不分页）；`tools/call`。其他方法 `-32601`；未知通知忽略；`initialize` 之前除 `ping` 外的请求 `-32002`（服务未初始化）。
- **工具定义**：名字、说明、`inputSchema` 全部由 P8 的工具规格生成（与 Agent 给模型的 schema 同源，`additionalProperties: false`）；每个工具加 `annotations: {"readOnlyHint": true, "destructiveHint": false, "idempotentHint": true, "openWorldHint": false}`。
- **调用结果**：`ToolRegistry.call(name, args)` 的结果原样转成一个 `text` 内容块（JSON：`{ok, status, data, asOf, records, truncated}`），同时放进 `structuredContent`；`ok=false`（含参数不合格、404、超时）→ `isError: true`。未知工具名 → JSON-RPC 错误 `-32602`。工具层的校验、16 KB 截断、只 GET、不跟随重定向全部沿用，MCP 层不另开任何入口。
- **不提供** `investigate`、resources、prompts、sampling：MCP 客户端自己的模型做推理，这里只给证据。
- **调用上限**：每个进程最多 500 次 `tools/call`，超出后返回 `isError` 结果 `call_limit_reached`（防止失控的客户端循环打满后端）。

### 4.1 怎么接到 Claude Code

文档给出示例（**不**写进仓库的 `.mcp.json`，免得每次开会话都去连一个没启动的后端）：

```json
{"mcpServers": {"ledgerlab-tools": {"type": "stdio", "command": "python",
  "args": ["agent/ledgerlab-agent-mcp.py", "--base-url", "http://127.0.0.1:8080"]}}}
```

## 5. 威胁边界（在 P8 第 9 节之上）

- 新增的两个进程都碰不到写接口：调查服务和 MCP 服务访问主应用只能经工具层（只 GET、固定模板、`routes.json` 由 AC-10.2 在服务端验证）；静态检查保证它们没有别的出站 HTTP。
- 写操作的唯一路径是人在浏览器里点“提交”，请求由前端按封闭映射组装，后端按 ADR-0011 全部重新校验（流水 reference、金额合计、批次状态……）。模型被注入后最坏能做的，是给出一个错误的预填；它过不了后端校验就不会写入，过得了的也必须有人点。
- 解释文字可能含注入文本（来自 memo）：前端只当纯文本显示；预填的 `reasonNote` 会把它带进纠错记录，这是人可见、可编辑的，且只是备注字段。
- 调查服务只监听回环地址、只认 JSON POST、不回 CORS；一次只跑一个调查；模型固定为不花钱的替身或基线。
- 不防：能在本机执行命令的人；人看都不看就点提交。

## 6. 交付物

- `agent/service/`（调查服务、存储、测试）、`agent/ledgerlab-agent-service.py`
- `agent/src/ledgerlab_agent/mcp_server.py`、`agent/ledgerlab-agent-mcp.py`、测试
- 前端：`frontend/src/agent.tsx`（面板与采纳表单）、`IssuePage` 接入、Vite 代理、单元测试
- E2E：新场景 `agent-workbench`（测试后端种一条拆分到账和一条手续费差异，Playwright 起调查服务（替身）），覆盖调查、引用跳转、两种采纳、调查服务未启动
- `docs/handoff/p9.md`、README 与 `agent/README.md` 的 P9 段落
