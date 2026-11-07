# P8-T07 外部模型适配器：DeepSeek（OpenAI 兼容格式，标准库 HTTP）

> 用户 2026-11-07 决定用 DeepSeek 的 API（不用 Anthropic）。本任务只写适配器和不联网的合同测试；**实现进程不得发出任何真实 API 请求**，真实评测由执行者在用户批准的费用上限内运行。

## 背景
读 `docs/design/investigation-agent.md` 第 4、10 节（第 10 节已按 DeepSeek 改写），AC-10.10。T01 的 `ModelClient` 协议、`model.py` 的消息与内容块、`agent.py` 的循环；T06/T06b 的评测器与报告。

DeepSeek 接口要点（2026-11-07 从 api-docs.deepseek.com 的 API 参考、Tool Calls、Thinking Mode 三页读到；价格页没有读到，**价格未核实**）：
- `POST https://api.deepseek.com/chat/completions`，头 `Authorization: Bearer <DEEPSEEK_API_KEY>`、`Content-Type: application/json`。OpenAI chat completions 格式。
- 模型名：`deepseek-flash`（当前 Flash），`deepseek-v4-pro`。工具调用在思考模式下可用。
- 思考模式默认开启：`"thinking": {"type": "enabled"|"disabled"}`，`"reasoning_effort": "none"|"low"|"high"|"max"`。回复里思考内容在 `choices[0].message.reasoning_content`。**带 `tools` 的请求，之前每一轮 assistant 消息的 `reasoning_content` 都必须原样回传，否则 400。** 思考模式下 `tool_choice` 只能用 `auto`（`required`／指定工具会 400）；`temperature` 等无效。
- `tools: [{"type":"function","function":{"name","description","parameters"}}]`；工具名只能 `[A-Za-z0-9_-]`、≤128。回复 `message.tool_calls[]`：`id`、`type:"function"`、`function.name`、`function.arguments`（**JSON 字符串，可能不是合法 JSON 或含多余参数**）。工具结果用 `{"role":"tool","tool_call_id","content"}`。
- `finish_reason`：`stop`、`length`、`content_filter`、`tool_calls`、`insufficient_system_resource`、`aborted`。
- `usage`：`prompt_tokens`、`completion_tokens`、`prompt_cache_hit_tokens`、`prompt_cache_miss_tokens`、`completion_tokens_details.reasoning_tokens`。
- `max_tokens` 1～393216。

## 范围
1. **不加依赖**：`agent/src/ledgerlab_agent/deepseek_model.py` 用 `urllib.request` 发 POST。这是 `agent/src` 里唯一允许的非 GET 网络调用：静态检查（`test_static.py`）只为这个文件开最小例外，且断言它只能连 `https://api.deepseek.com`（基址写死为常量，测试可注入假 transport；不接受环境变量改基址）。工具层（`tools.py`、`transport.py`）的 GET-only 保证不变。
2. **内容块扩展**：`model.py` 增加 `ReasoningBlock{text, provider}`（不可见给评测、不进报告），assistant 回复里的思考内容存成它，Agent 循环把 assistant 回复原样追加进对话；DeepSeek 适配器把它还原成 `reasoning_content`。Scripted/Stub 模型不受影响。
3. **`DeepSeekModel(model, reasoning_effort, max_tokens, transport=None)`** 实现 `ModelClient`：
   - 把内部消息（system、user、assistant 的 text/tool_use/reasoning 块、tool_result）转成 OpenAI 格式；`tool_choice:"auto"`；工具 schema 来自现有生成逻辑（名字已符合规则；若 `strict` 需要 beta 基址则不用 strict）。
   - 把回复转回 `ModelResponse`：`tool_calls` → `ToolUseBlock`（`arguments` 解析失败时生成一个 `ToolUseBlock`，其 input 标记为无效，让 Agent 循环按“无效参数”回传错误，不崩溃）；`finish_reason` 映射到 `stop_reason`；`length`、`content_filter`、`insufficient_system_resource`、`aborted` 记为不可继续，Agent 以 `invalid_output`（或新增的 `provider_error`）结束，不自动重试。
   - HTTP 429/5xx：最多重试 2 次（指数退避，时钟可注入）；4xx 不重试。
   - 密钥只从环境变量 `DEEPSEEK_API_KEY` 读；缺失时报清楚的错。任何日志、异常信息、报告、`runs.log` 都不得出现密钥（测试用假密钥字符串断言输出里搜不到）。
   - `usage` 映射：输入 = cache hit + miss，另外分别保存 hit、miss、reasoning_tokens。
4. **价格与花钱保护**：`agent/pricing.json` 增加 `deepseek` 段，字段 `input_cache_hit`、`input_cache_miss`、`output`（美元／百万 token）、`source_url`、`checked_at`；**先全部填 `null`**。`eval --model deepseek:<model>`：
   - 价格为 `null` 时拒绝运行，提示先核实价格；
   - 运行前按“每题预计 token”（默认用替身估算的输入 token × 3、输出 2000，可配置）打印预计费用；
   - 没有 `--confirm-spend <美元>` 不发请求；运行中按实际 usage 累计费用，超过上限立即停止，已完成的题写报告并标“未完成（达到费用上限）”；
   - `--split` 可选 dev／test／hard，另加 `--limit N`（只跑前 N 题，用于冒烟）。
5. **CLI**：`investigate <issue_id> --model deepseek:<model> --confirm-spend <美元>` 同样受保护。
6. **报告**：外部模型列能填入 DeepSeek 结果（模型名、reasoning_effort、价格来源和日期、实际费用）。`runs.log` 照常。
7. **系统提示**：`prompts/system.md` 如需为外部模型补充（如“只用 submit_conclusion 结束”“引用必须来自工具结果”），可以改，但只能依据 dev 集的考虑；改了就在设计第 4 节实现备注写明。
8. 回写设计第 4、10 节实现备注。

## 关键测试场景（不联网，假 transport 返回手写夹具）
- `test_ac_10_10_*`：纯文本回复、单个 tool_call、多个 tool_call、`arguments` 非法 JSON、含多余参数、`finish_reason` 各值、429 后成功、4xx 不重试、usage 映射、`reasoning_content` 在后续请求里原样回传（断言第二次请求体里有第一轮的 reasoning_content）、思考模式下请求体里 `tool_choice` 是 `auto`。
- 花钱保护：价格为 null 拒绝；无 `--confirm-spend` 不调用 transport；累计超过上限后停止并写“未完成”报告。
- 密钥不外泄：设置假密钥后跑一遍（包括失败路径），所有输出和写出的文件里搜不到。
- 静态检查：只有 `deepseek_model.py` 出现 POST；基址常量是 `https://api.deepseek.com`。
- 用 ScriptedModel 跑的现有测试全部照过；`eval.sh --check` 照过。

## 可改范围
`agent/**`（不改冻结数据集的 snapshot/labels/manifest）、`docs/design/investigation-agent.md`、`docs/eval/**`（只在需要时重生成报告）、`scripts/agent/**`。

## 完成标准
`python -m unittest discover -s agent/tests -t agent` 全绿；`sh scripts/agent/eval.sh --check` 退出 0；没有发出任何真实网络请求。提交带 `Task: P8-T07`。
