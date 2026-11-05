# P7-T09 P7 验收清单与收尾

## 背景
`docs/handoff/p5.md`（验收清单格式）；P7-T01～T08 的报告结论。

## 范围
1. `docs/handoff/p7.md`：AC-9.1～9.12 每条 → 测试或脚本、证据文件、结果；需要用户做的事（AWS 报价确认与账号、视频是否加旁白、文章是否发布）；已知限制。
2. `docs/design/phases.md` 当前状态更新（P7 本地部分完成，部署待用户确认）。
3. 清点 P7 留下的 TODO/待定，列在最后输出。

## 可改范围
`docs/**`、README。

## 完成标准
本地提交（`Task: P7-T09`）。

## 执行者验证
全量 verify、simulator verify、`cd frontend; npm run check; npm run test:e2e`、`sh scripts/smoke-p5.sh`、`sh scripts/demo/check-reset.sh`，结果写进报告。
