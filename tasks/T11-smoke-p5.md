# T11 smoke-p5 与收尾

## 背景
`scripts/smoke-p4.sh`（照着写）；AC 演示脚本对应表。

## 范围
1. `scripts/smoke-p5.sh`：干净 compose 环境起应用＋模拟器，跑一条正常提现（受理 → 银行受理 → 出账 → PAID）、一条执行后丢回执恢复、一条拒绝（FAILED）、一条退回，最后跑对账、断言不变量与余额。脚本结束清理容器和卷。
2. README 里“怎么跑”的命令列表加 smoke-p5（不改其他段落）。
3. 设计文档里 P5 任务留下的“待定/TODO”清点，列在最后输出里（不改设计决定）。

## 可改范围
`scripts/smoke-p5.sh`、README 的命令列表、compose 冒烟配置（只增不改）。

## 完成标准
按 `_common.md` 第 5 条，并实际跑 `sh scripts/smoke-p5.sh`；本地提交（`Task: T11`）。
