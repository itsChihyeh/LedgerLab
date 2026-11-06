# P9-T02 MCP 只读工具服务（stdio，标准库）

## 背景
读 `docs/design/agent-workbench-and-mcp.md` 第 4、5 节，ADR-0020 第 4 条，AC-11.7、AC-11.8。看 P8 的 `agent/src/ledgerlab_agent/tools.py`（`ToolRegistry`、`ToolSpec`、`ToolResult`）、`model.py`（`tool_definitions()`：给模型的 JSON Schema，MCP 的 `inputSchema` 要与它同源）、`sop.py`（`SopSearch`）、`replay.py`、`cli.py`、`agent/tests/test_static.py`。协议以 MCP 规范 2025-11-25 为准（生命周期、`tools/list`、`tools/call`、`CallToolResult` 的 `content`／`structuredContent`／`isError`、工具 `annotations`）；有疑问用 Context7 查 `/websites/modelcontextprotocol_io_specification_2025-11-25`，不凭记忆。

## 范围
1. `agent/src/ledgerlab_agent/mcp_server.py`：放在 P8 核心包里，**必须通过核心包现有的 `test_static.py`，不许改那个测试**（stdin/stdout 读写不触犯它的模式；如果确实触犯，停下来问）。结构建议：一个纯函数 `handle(message: dict, state) -> dict | None`（便于单测），外加一个读 stdin、写 stdout 的循环。
2. 启动器 `agent/ledgerlab-agent-mcp.py`：`--base-url` 与 `--snapshot` 二选一（与 CLI 同样的校验），`--max-calls`（默认 500）。参数错误退出 2，错误写 stderr。
3. 第 4 节全部行为：逐行 JSON-RPC；版本协商（2025-11-25／2025-06-18／2025-03-26，其他回 2025-11-25）；`initialize` 的 `capabilities`、`serverInfo`、`instructions`；`ping`；`tools/list`（12 个工具：`routes.json` 的 11 个＋`search_sop`，名字与 P8 一致；`inputSchema` 来自 `model.tool_definition(spec)`（**不含** `submit_conclusion`：那是 Agent 循环内部的提交动作，不是证据工具）；每个都带 `annotations` 四个提示）；`tools/call`（经 `ToolRegistry.call`，结果 `text`＋`structuredContent`，`ok=false` → `isError: true`）；错误码 `-32700`（解析失败，`id: null`）、`-32600`（批量或不是合法请求）、`-32601`、`-32602`（未知工具或 `arguments` 不是对象）、`-32002`（初始化前）；通知不回复；调用上限 `call_limit_reached`。stdout 只写协议消息，每条以 `\n` 结尾并 flush；任何异常写 stderr，并回 `-32603`，进程不退出。stdin 关闭时正常退出 0。
4. 不实现 resources、prompts、sampling、`notifications/tools/list_changed`、2026-07-28 的无状态模式；`initialize` 的回包里不声明这些能力。
5. `agent/README.md` 加“MCP 服务”一小节：启动命令、Claude Code 的示例配置（设计第 4.1 节，**不改仓库根的 `.mcp.json`**）、工具一览（从 `routes.json` 来，写“同 Agent 的工具”即可，不要手抄一份会过时的表）。回写设计第 4 节实现备注。

## 关键测试场景（`agent/tests/test_mcp_server.py`）
- `test_ac_11_7_…` 单元层（直接调 `handle`）：握手三种受支持版本原样回、未知版本回 2025-11-25；`initialize` 之前 `tools/list` → `-32002`、`ping` 正常；`tools/list` 的名字集合 == `ToolRegistry` 的工具名集合，`inputSchema` == `model.tool_definition(spec)` 的 schema、列表里没有 `submit_conclusion`，每个都 `readOnlyHint: true`、`destructiveHint: false`；未知方法 `-32601`；未知工具 `-32602`；批量数组 `-32600`；通知（无 `id`）不回复。
- `test_ac_11_7_…` 进程层：用 `sys.executable` 起 `agent/ledgerlab-agent-mcp.py --snapshot agent/eval/data/dev/snapshot.json` 子进程（测试代码可以用 `subprocess`，静态检查只扫 `agent/src`），走完 `initialize` → `notifications/initialized` → `tools/list` → `tools/call get_issue`；坏 JSON 一行得 `-32700` 后进程仍在服务；stdout 每一行都是合法 JSON-RPC 消息；关 stdin 后退出码 0。
- `test_ac_11_8_…`：`tools/call` 用不合正则的参数 → `isError: true` 且（用记录请求的假 transport 证明）没有发出 HTTP；对 dev 快照里一条差异，MCP 的 `get_issue`、`search_bank_lines` 结果的 `structuredContent` 与直接 `ToolRegistry.call` 的结果相同；`search_sop` 能用；`--max-calls 2` 时第 3 次调用得到 `call_limit_reached`；P8 的 `test_static.py` 仍全绿（它会扫到新模块）。

## 可改范围
`agent/src/ledgerlab_agent/mcp_server.py`（新建）、`agent/ledgerlab-agent-mcp.py`（新建）、`agent/tests/test_mcp_server.py`（新建）、`agent/README.md`、`docs/design/agent-workbench-and-mcp.md`。`agent/src/ledgerlab_agent/` 其他模块只允许向后兼容的小改动（例如把 CLI 里组装 transport 的代码抽成公开函数；若 T01 已经抽过，就复用它）。

## 完成标准
`python -m unittest discover -s agent/tests -t agent` 与 `python -m unittest discover -s agent/service/tests -t agent/service` 全绿；`sh scripts/agent/eval.sh --check` 退出 0 且无新改动；最后输出里贴一段手动用管道喂给 MCP 服务的 `initialize`＋`tools/list`（只贴工具名）＋一次 `tools/call` 的实际输出摘要。提交带 `Task: P9-T02`。
