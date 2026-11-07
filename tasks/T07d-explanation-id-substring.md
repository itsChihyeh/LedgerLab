# P8-T07d 解释里的编号：作为子串出现在看过的值里也算看过

## 背景
dev 第二次 DeepSeek 运行（57/58）剩下的 3 个“编造引用”全在 BANK_UNKNOWN_CREDIT 题的**解释文字**里，例如解释写“reference "unknown_po_599d…"……get_batch po_599d… returned 404”。`po_599d…` 是成功结果里字符串值 `unknown_po_599d…`、`bt_po_599d…_2` 的一部分，模型也确实用它调过工具。现在的核验器只认“完整字符串值”，把它算成编造，不符合设计“不存在或没看过”的定义。

## 范围
1. `verify.py`：解释里抽出的编号，若作为**子串**出现在任一成功工具结果的某个字符串值里（按编号边界匹配：前后不是字母数字），就算看过，不计编造。结论 `citations` 里的引用口径不变（仍按 T07b 的三类）。
2. 设计第 8.3 节 P8-T07b 备注补一句这个规则。
3. 测试：`unknown_po_x` 里的 `po_x` 算看过；`po_xy`（更长）不能让 `po_x` 算看过；只出现在 503 结果里的不算。
4. 重算：用 `agent/eval/run_eval.py` 能否对已有的 dev-2 结果重新核验？如果评测输出里没有足够信息，就不重算，只保证新代码对以后的运行生效。基线/替身数字不变，`eval.sh --check` 照过。

## 可改范围
`agent/src/ledgerlab_agent/verify.py`、`agent/tests/**`、`docs/design/investigation-agent.md`。不读 test/hard 数据文件。

## 完成标准
单元测试全绿；`eval.sh --check` 退出 0；提交带 `Task: P8-T07d`。
