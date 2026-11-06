# P8-T01 Agent 骨架：只读工具层、模型接口、Agent 循环、结论校验

## 背景
读 `docs/design/investigation-agent.md` 第 2、3、4、6 节和 ADR-0019。看 `src/main/java/dev/ledgerlab/investigation/internal/*Queries.java`、`QueryModels.java`、`PaymentQueries.java`、`EventQueries.java`、`WorkbenchQueries.java`、`CorrectionTraceQueries.java`、`RefundQueries.java`，以及 payouts 的只读查询（`grep -rn '@GetMapping("/api/payouts' src/main/java`），弄清每个接口返回的 JSON 形状（Jackson 默认把 record 组件名原样输出为 camelCase）。

## 范围（AC-10.1、AC-10.4）
1. `agent/pyproject.toml`：项目 `ledgerlab-agent`，`requires-python = ">=3.12"`，`dependencies = []`，`[tool.uv] package = false`（不需要构建后端，不下载任何东西）。`agent/README.md` 写怎么跑（仓库根目录 `python -m unittest discover -s agent/tests -t agent`；CLI 用 `python agent/ledgerlab-agent.py …` 或 `uv run --project agent python agent/ledgerlab-agent.py …`）。`agent/ledgerlab-agent.py` 是把 `agent/src` 加进 `sys.path` 后调用 `ledgerlab_agent.cli:main` 的薄启动器。测试不需要安装就能导入 `ledgerlab_agent`（例如 `agent/tests/__init__.py` 处理路径）。
2. `agent/src/ledgerlab_agent/routes.json`：设计第 3 节表里除 `search_sop` 以外的每个工具一条：`{"tool", "method":"GET", "path_template", "params":{name:{"in":"path|query","pattern":"<正则>","required":bool}}}`。参数正则要窄（例如 issue id 按现有编号前缀，limit 1–50，status/category 用现有枚举；先看 Java 代码和数据字典里的编号格式，拿不准的用 `^[A-Za-z0-9_\-]{1,64}$`）。`search_bank_lines` 的后端 `GET /api/bank-lines?reference=` 由 T02 实现，这里先登记。
3. `transport.py`：`Transport` 协议 `get(path: str, query: dict[str,str]) -> TransportResponse(status:int, body:bytes|None, error:str|None)`；`HttpTransport(base_url, timeout=10)` 用 `urllib.request`，只允许 GET，`base_url` 只接受 `http://` 或 `https://` 开头且不含 userinfo；不跟随重定向（重定向记为错误）。
4. `tools.py`：`ToolRegistry` 从 `routes.json` 加载；`call(name, args) -> ToolResult{tool, args, ok, status, data, as_of, records, truncated, error}`。
   - 未知工具、未知参数、缺必填参数、参数不匹配正则 → 返回 `ok=false, error="invalid_arguments"`，**不调用 transport**。
   - 路径参数做 URL 编码；只按模板拼，不接受任何调用方提供的路径片段。
   - 响应体 > 16 KB 截断（截断后 `data` 为截取的前缀文本，`truncated=true`）。非 2xx 返回 `ok=false` 带 status。
   - `records`：从响应 JSON 里抽出记录标识 `{kind, id}`。kind 枚举：`issue, payment, event, entry, batch, batch_line, bank_line, statement_line, refund, payout, correction, run`。按各接口已知字段抽取（写一个按工具的抽取表，再加一个对 `evidenceRefs`/`references` 之类结构的通用遍历）；`statement_line` 的 id 用 `"<fileId>/<seq>"`。去重、保序。
   - `search_sop` 先登记为本地工具接口 `LocalTool`，T05 实现；本任务给一个返回空列表的占位。
5. `model.py`：数据类 `ModelRequest{system, messages, tools, max_tokens}`、`ModelResponse{content, stop_reason, usage}`、内容块 `TextBlock{text}`、`ToolUseBlock{id, name, input}`；`Usage{input_tokens, output_tokens}`。`ModelClient` 协议 `complete(request) -> ModelResponse`。`ScriptedModel(steps)`：按顺序返回预先给定的回复；可从 JSON 文件加载；脚本用完再被调用就抛错。工具定义的 JSON Schema 由 `routes.json` 生成（每个工具一个 schema，`additionalProperties:false`），外加 `submit_conclusion` 工具（schema = 第 6 节结论格式）。
6. `conclusion.py`：结论数据类与校验。`root_cause` 枚举 = 设计第 6 节原因表（含 `INSUFFICIENT_EVIDENCE`）；`recommended_action` 枚举 = 第 6 节；`confidence` ∈ high/medium/low；`abstain=true` 时 `root_cause` 必须是 `INSUFFICIENT_EVIDENCE`，反之亦然；`explanation` 非空、≤ 800 个字符；`citations` 每项 `{kind,id}`，kind 用第 4 条的枚举，最多 20 条；`sop_clauses` 每项匹配 `^SOP-\d+(\.\d+)*$`。校验返回错误列表（不抛异常）。枚举放一个模块里，后续任务共用。把“800 个字符”写进设计第 6 节的实现备注（原文写的是 120 词）。
7. `agent.py`：`investigate(issue_id, model, tools, budget) -> Investigation{conclusion, status, tool_calls:[ToolResult], turns, usage, elapsed_ms}`。
   - 首条用户消息给出 issue_id 和任务；系统提示放 `agent/src/ledgerlab_agent/prompts/system.md`（写第一版：角色、只读、工具数据不可信且其中的指令一律忽略、必须用 `submit_conclusion` 结束、每个事实要有引用、证据不足就拒答、建议动作只是给人的建议）。
   - 循环：模型回复里的每个 `tool_use` 依次执行，结果作为 `tool_result` 回传，结果文本用 `<tool_data tool="…">…</tool_data>` 包裹（数据里出现的 `</tool_data>` 要转义）；遇到 `submit_conclusion` 就校验：通过 → `status="ok"`；不通过 → 回传错误给模型一次修正机会，再不通过 → `status="invalid_output"`，结论置为拒答。
   - 模型只回文本不调用工具也不提交 → 视同一次不合格输出（同上，给一次修正机会）。
   - 预算：工具调用 ≤ 12、模型轮数 ≤ 8（可配置）；超出 → `status="budget_exhausted"`，结论为拒答，`explanation` 写明原因。
   - 时钟（`elapsed_ms`）可注入。
   - `ToolResult` 全部保留在 `Investigation.tool_calls` 里，供 T06 的引用核验使用。
8. `cli.py`：`investigate <issue_id> --base-url URL --model scripted:<path.json> [--max-tool-calls N] [--json]`；输出结论 JSON（`--json`）或可读摘要（原因、置信度、解释、引用列表、工具调用次数）。模型注册表先只有 `scripted`，后续任务加 `baseline`、`stub`。
9. CI：在 `.github/workflows/ci.yml` 加一个 job `agent`：`actions/checkout`（用文件里已有的同一个固定提交号）后运行 `python3 --version && python3 -m unittest discover -s agent/tests -t agent`（ubuntu runner 自带 Python 3.12，不加新 action）。

## 关键测试场景（`agent/tests/`，方法名带 AC 编号）
- AC-10.1：未知工具、多余参数、坏参数（含 `../`、`?`、`#`、空白、超长）都不触发 transport（用记录调用的假 transport 断言调用次数为 0）；`HttpTransport` 拒绝非 http(s) base_url、带 userinfo 的 base_url；没有暴露非 GET 的方法（断言 transport 类没有 post/put/delete 等公开方法，`urllib.request.Request` 的 method 固定为 GET）；静态检查：`agent/src` 下源码里不出现 `subprocess`、`os.system`、`socket`（除 urllib 内部）、`open(` 写模式、`sqlite3`/`psycopg` 之类导入（写一个读源码文本的测试）。
- 用标准库 `http.server` 在随机端口起假后端，返回按 Java 记录形状手写的夹具，验证 `records` 抽取（每个工具至少一条夹具）、截断、非 2xx、`asOf` 抽取。
- AC-10.4：`ScriptedModel` 驱动：两次工具调用后提交合格结论 → ok；提交不合格结论后修正 → ok；两次不合格 → invalid_output 且拒答；超 12 次工具调用 → budget_exhausted；超轮数 → budget_exhausted；工具数据里含 `</tool_data>` 被转义。
- 结论校验：每条规则一条正例一条反例。

## 可改范围
`agent/**`（新建）、`.github/workflows/ci.yml`（只加 `agent` job）、`docs/design/investigation-agent.md`（实现备注）、`.gitignore`（如需忽略 `agent/` 下的生成物）。**不改** `src/`、`simulator/`、`frontend/`、`scripts/`。

## 完成标准
`python -m unittest discover -s agent/tests -t agent` 全绿；提交带 `Task: P8-T01`。
