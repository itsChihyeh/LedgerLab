# P9-T05 验收清单与说明文档

## 背景
读 `docs/design/agent-workbench-and-mcp.md` 全文（含 T01～T04 的实现备注）、ADR-0020、AC-11，参考 `docs/handoff/p8.md` 的结构与语气（结论先行、每条 AC 一行、诚实写限制）。看 `git log --format='%h %s%n%b' p8..p9` 了解每个任务做了什么。

## 范围
1. `docs/handoff/p9.md`：
   - 开头一段：P9 是什么、设计与决策链接、“仍只用规则基线和本地替身，没有调用外部模型、没有费用”。
   - 合并方式：`p9` 叠在 `p8` 上，跟随 p6→p7→p8 的合并顺序；`phases.md` 当前状态一句话在各分支都改过，合并时保留各分支的句子。
   - 自己验收：命令清单（Python 两组单测、`eval.sh --check`、`npm run check`、`npm run test:e2e -- --scenario=agent-workbench`、手动起调查服务与 MCP 服务的命令，含 MCP 的 Claude Code 示例配置），以及“作者实际跑过的结果”表，和一张留给执行者填写的空表（全量 `./mvnw verify`、模拟器 verify、E2E 全部场景、MCP 实机连接）。
   - 每条 AC-11.x 怎么验证（层、测试名、证据、结果），反查命令 `git grep -n ac_11_ agent frontend src/test`。
   - 值得看一眼的地方：采纳为什么只开放两种原因；结论为什么存在调查服务一侧；零写权限分哪几层落地；MCP 为什么不用 SDK、不实现 2026-07-28 无状态模式。
   - 需要用户做的事（外部模型仍待批准；是否在 README 展示截图）。
   - 已知限制（替身不代表大模型；调查服务单进程同步、一次一个；记录在本机文件；生产构建不带调查服务；只有两种可采纳原因；MCP 只支持旧式握手和 stdio 等）。
2. README：在 Agent 一节后加 P9 的两三句话和链接（工作台调查面板、MCP 服务）；`agent/README.md` 检查 T01、T02 加的小节是否准确。
3. `docs/design/phases.md` 当前状态里 P9 那一句改成“P9 本地部分完成”，并链到 `p9.md`。

## 可改范围
`docs/handoff/p9.md`（新建）、`README.md`、`agent/README.md`、`docs/design/phases.md`、`docs/design/agent-workbench-and-mcp.md`（只改措辞）。不改代码。

## 完成标准
文档里引用的每个命令都实际跑过一次（E2E 可只跑 `agent-workbench` 场景），结果如实填进“作者实际跑过的结果”；全量 verify 留给执行者。不写任何密钥形状的字符串。提交带 `Task: P9-T05`。
