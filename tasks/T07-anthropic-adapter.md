# P8-T07 外部模型适配器（用户批准后才开工）

> 状态：草稿，等待用户批准（SDK 可选依赖、`ANTHROPIC_API_KEY`、模型与费用上限）。批准内容写进 `answers/T07.md` 后再派发。

## 背景
读 `docs/design/investigation-agent.md` 第 4、10 节，AC-10.10。T01 的 `ModelClient` 协议、T06 的评测器和报告、T06b 的 hard 集。

## 范围
1. `agent/pyproject.toml` 加可选依赖组 `anthropic`（官方 SDK `anthropic`，固定到批准时的版本）；运行时代码在 SDK 缺失时给出清楚的报错和安装命令，其余功能不受影响。`agent/src` 的静态检查为 SDK 导入开一个最小例外（只允许在 `anthropic_model.py` 里）。
2. `anthropic_model.py`：`AnthropicModel(model, effort, max_tokens)` 实现 `ModelClient`；把 `ModelRequest` 转成 Messages API 请求（工具定义来自 routes.json 和 `submit_conclusion`，系统提示可缓存），把响应转回 `ModelResponse`（含 usage，含缓存读写 token）；处理 `stop_reason` 为 `refusal`、`max_tokens` 的情况（记为 `invalid_output`，不重试花钱）；密钥只从环境变量读，任何日志、报告、异常信息里都不出现密钥。按 SDK 文档写，不凭记忆。
3. `agent/pricing.json`：批准时的每百万 token 价格（输入、输出、缓存读、缓存写），注明来源和日期。
4. 花钱保护：`eval --model anthropic:<model>` 先打印预计费用上限；没有 `--confirm-spend <美元>` 不发请求；运行中按实际 usage 累计费用，超过上限立即停止，写已完成部分的报告并标“未完成”。
5. 合同测试（不联网）：用手写的响应夹具覆盖文本、tool_use、多个 tool_use、refusal、max_tokens、usage 映射、费用累计与停止、密钥不外泄。
6. 报告“外部模型”列接上；`runs.log` 照常记录。

## 可改范围
`agent/**`（不改冻结数据）、`docs/design/investigation-agent.md`、`docs/eval/**`。真实联网评测由执行者在用户批准的上限内运行，不由实现进程运行。
