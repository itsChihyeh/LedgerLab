# P8-T07c 预算提示、最后一轮必须提交、调查轨迹落盘（依据 dev 第一次完整运行）

## 背景
执行者用 deepseek-flash 跑了完整 dev（66 题，花 0.36 美元）：
- 原因准确率 44/58，拒答召回 8/8，**误拒 14**；
- **20 题以 `budget_exhausted` 结束**（12 次工具调用用完），包括 14 道本该答出的题和 6 道截留题（截留题因为预算耗尽而“拒答”，不是因为模型判断证据不足）；
- 编造引用 2（dev-p004、dev-p041，都是 BANK_UNKNOWN_CREDIT），输出不合格 2（dev-p017 WEBHOOK_LOST、dev-p031 FOREIGN_CURRENCY）；
- SOP 条款一致 38/66；平均工具调用 9.65。

主要问题是模型不知道预算，探索太多，到上限时还没提交。评测输出里没有逐题轨迹，执行者没法看编造和不合格的具体内容。

## 范围
1. **调查轨迹**：`eval` 和 `investigate` 加 `--trace-dir <目录>`：每题写一个 JSON（结论或失败原因、每次工具调用的工具名／参数／状态／是否截断／抽出的记录、模型每轮的可见文本、`stop_reason`、usage、核验结果：编造和格式不规范的具体条目）。**不写** `reasoning_content` 原文（只写它的字符数）、不写密钥。默认不写；目录建议 `target/agent-live/…`（已被 gitignore）。
2. **预算提示**：每次把工具结果回传给模型时，在同一条用户消息末尾（工具结果之后）附一行 Agent 自己写的提示（不在 `<tool_data>` 里）：已用工具调用 x/12、模型轮数 y/8；剩余工具调用 ≤ 3 时提示改为“只剩 n 次，下一轮请调用 submit_conclusion”。
3. **最后一轮必须提交**：工具调用或轮数用完时，不直接判 `budget_exhausted`，而是再给模型**一轮**：只提供 `submit_conclusion` 一个工具，附提示“预算已用完，请根据已有证据提交结论；证据不足就拒答”。这一轮提交合格 → 照常核验；不提交或不合格 → `budget_exhausted`（与现在一样）。这一轮计入模型轮数统计，在报告里单独计数“最后一轮才提交”的题数。
4. **系统提示**（依据 dev 观察，写进设计第 4 节实现备注）：补一句工作顺序建议——先 `get_issue`，再按差异类别 `search_sop` 找对应条款，按条款的“必须查看的证据”取证，证据够了就提交，不要读与判别无关的记录；预算是 12 次工具调用。
5. ScriptedModel、StubModel、基线的行为不变（替身本来 3 轮内提交，不会碰到预算；预算提示文字进入对话后，替身解析 `<tool_data>` 不受影响——加测试证明）。`eval.sh --check` 照过，基线/替身数字不变。
6. 设计第 3 节（预算）加实现备注：预算提示和最后一轮的规则。

## 测试（不联网）
- ScriptedModel：剩 3 次时提示文字出现；用完预算后最后一轮只有 `submit_conclusion` 工具；最后一轮提交合格 → ok（标记 `final_turn_submit`）；不提交 → budget_exhausted。
- 轨迹文件：字段齐全；不含 reasoning 原文、不含假密钥。
- 替身在带预算提示的对话里结论不变。

## 可改范围
`agent/src/ledgerlab_agent/agent.py`、`model.py`、`evaluate.py`、`cli.py`、`prompts/system.md`、`agent/eval/run_eval.py`、`agent/tests/**`、`docs/design/investigation-agent.md`、`docs/eval/**`（如需重生成）。不改冻结数据、`baseline.py`、`stub_model.py`、SOP、`deepseek_model.py`（除非为最后一轮的工具列表必须改，改了要说明）。不读 test/hard 数据文件。

## 完成标准
单元测试全绿；`eval.sh --check` 退出 0、基线/替身数字不变；提交带 `Task: P8-T07c`。
