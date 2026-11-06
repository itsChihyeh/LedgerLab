# P8-T08 验收清单与 README

## 背景
读 `docs/design/investigation-agent.md`（含全部实现备注）、ADR-0019、AC-10、`docs/eval/` 下的探测报告和评测报告、`agent/README.md`。格式照抄 `docs/handoff/p7.md`（同样的小节：合并方式、自己验收的命令、每条 AC 怎么验证的、值得看一眼的地方、需要用户做的事、已知限制）。

## 范围
1. `docs/handoff/p8.md`：
   - 一段话说明 P8 是什么；明确写“外部模型尚未接入，评测报告里的数字只来自规则基线和本地替身”。
   - 自己验收的命令（Python 单元测试、`sh scripts/agent/eval.sh --check`、`sh scripts/agent/verify-eval.sh --split dev`、`./mvnw -B test -Dtest='AgentRouteAllowlistTest,BankLineSearchTest'`，以及用替身调查一条差异的命令和示例输出）。执行者验证结果一栏留“待填”。
   - AC-10.1～AC-10.10 逐条：层、测试或脚本（`git grep` 能反查的测试方法名）、证据、结果。AC-10.10 写“未开始：等待用户批准外部 API（依赖、密钥、费用上限）”。
   - 值得看一眼：工具层的只读保证是怎么分三层落地的（Python 工具层、服务端路由测试、数据库角色）；回放快照的局限；“替身＝基线经模型接口”的含义；探测发现的注入时点问题；dev `p006` 动作碰巧相同的处理。
   - 需要用户做的事：批准外部模型接入（列出要加的依赖、需要的账号与密钥、费用估算与上限的选择——费用估算一节写“由 Claude 在交付时给出”，不要自己编数字）。
   - 已知限制（如实写：封闭原因表、规则在 dev 上满分说明的是覆盖而不是能力、爬取范围、`asOf` 是生成时刻、memo 只是唯一的注入面）。
2. 根目录 `README.md` 加简短的 “Investigation agent (P8)” 一节（3～6 行英文或与 README 现有语言一致）：它是什么、只读、怎么跑替身评测、链接到 `docs/handoff/p8.md` 和评测报告。不改 README 其他部分。
3. `docs/design/phases.md` 的“当前状态”一行补一句 P8 的状态（本地部分完成、外部模型待用户批准），不改表格其他行。

## 可改范围
`docs/handoff/p8.md`（新建）、`README.md`（只加一节）、`docs/design/phases.md`（只改当前状态那一段）、`agent/README.md`（如需与交接文档一致）。不改代码。

## 完成标准
文档里的每个命令你都实际跑过一次（Maven 那条只跑这两个测试类；Docker 的 verify-eval 可以不跑，写“由执行者跑”），结果写进最后输出；提交带 `Task: P8-T08`。
