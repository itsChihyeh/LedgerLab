# P8-T07b 引用“看过”的判定对齐设计；系统提示写明引用格式

## 背景
执行者用 DeepSeek（deepseek-flash）在 dev 前 3 题做了付费冒烟：3/3 答对，但核验器报了 4 个编造引用。逐条看后发现不是模型编造：
- 模型引用了 `entry je_…`、`payment pay_…`，这些编号**确实出现在**它读过的工具结果里（例如支付时间线、批次详情里的 postings/entries），只是 T01 的 `records` 抽取没有把它们抽成记录；
- 模型把结算单行引用成 `{"kind":"statement_line","id":"txn_…"}`，而工具层约定的 id 是 `<fileId>/<seq>`；模型并不知道这个约定。

设计第 8.3 节对编造引用的定义是“不存在或没看过”，现在的实现（只认 `records`）比设计更严，会把真实看过的记录算成编造，外部模型的这一项数字就不诚实了。

## 范围
1. `verify.py`：引用分三类计数，报告都列出：
   - **有效**：`(kind,id)` 在某个成功工具结果的 `records` 里（现有口径）；
   - **看过但类别或格式不对**：`id` 作为完整的 JSON 字符串值（或 `<fileId>/<seq>` 中的两段都）出现在某个成功工具结果的原始数据里，但不满足第一条；这类**不算编造**，单列“引用格式不规范”；
   - **编造**：以上都不满足。
   解释里的编号同理：在成功工具结果的原始数据里出现过就不算编造。截断的结果按截断后实际给模型看到的文本判断。失败结果（含 503 截留）里的内容不算看过。
   - 硬线仍是“编造 = 0”；“格式不规范”只报告不设线。
   - 引用支持率只用“有效”的引用计算（保持严格）。
2. `records.py`：检查支付时间线、批次详情、差异详情、银行流水等响应里嵌套的分录、posting 所属分录、支付、事件编号是否都被抽成记录；漏的补上（这是 T01 抽取表的缺口），并补测试。
3. `prompts/system.md` 和 `submit_conclusion` 的 schema 描述：写明每种 `kind` 的 id 格式（尤其 `statement_line` 用 `<fileId>/<seq>`，`bank_line` 用 `bt_…`，`entry` 用 `je_…`），以及“只引用工具结果里出现过的编号，按它在结果里的形式原样抄写”。这是基于 dev 冒烟的观察，在设计第 4 节实现备注写明。
4. 基线和替身的结论不变；`sh scripts/agent/eval.sh --check` 照过（dev/test/hard 的基线与替身数字应完全不变，变了就停下来报告）。重新生成 `docs/eval/agent-<今天日期>.md`（外部模型列暂空，由执行者稍后填）。
5. 回写设计第 8.3 节实现备注（三类引用的定义）。

## 测试
- 三类各有正反例：在 postings 里出现的 je_ 算看过；txn_ 作 statement_line 算格式不规范；完全没出现的编号算编造；只出现在 503 结果里的算编造；只出现在截断部分之外的算编造。
- 用执行者的冒烟记录形态构造一个 Investigation（不需要真实调用），断言编造为 0、格式不规范为 1。
- 不联网；不读 `agent/eval/data/test/` 与 `agent/eval/data/hard/` 的数据文件。

## 可改范围
`agent/src/ledgerlab_agent/verify.py`、`records.py`、`evaluate.py`（报告字段）、`model.py`（仅 schema 描述）、`prompts/system.md`、`agent/eval/run_eval.py`（报告）、`agent/tests/**`、`docs/design/investigation-agent.md`、`docs/eval/**`。不改冻结数据、`baseline.py`、`stub_model.py`、SOP。

## 完成标准
单元测试全绿；`eval.sh --check` 退出 0 且基线/替身数字不变；提交带 `Task: P8-T07b`。
