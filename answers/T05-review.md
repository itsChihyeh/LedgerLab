# P7-T05 审查意见（云端，2026-11-05，不需要重跑）

1. JdbcRefunds 改动：看过 diff。生产上下文没有任何 RefundLimitGuard bean，`getIfAvailable` 退回原来的 `LockedRefundLimitGuard`，行为不变；`RefundLockSwitchTest` 证明 default/demo 里没有故障类。接受。T09 验收清单把这处列为“为 AC-9.2 做的最小装配调整”。
2. Dockerfile/.dockerignore、playwright.config 修正：接受。
3. 沙箱报告面板先显示通用数字：接受，P6 T08 定格式后再收窄（P6 T08 的任务里会顺带提醒）。
4. SSE 名额释放延迟：T09 前加一个小改动——页面 `beforeunload`/`pagehide` 时主动关闭 EventSource，保活间隔改 5 秒；若改动超过 20 行就只写进 T09 的已知限制。
5. 杀 8080/8090 上的进程：以后只清理本任务自己按 PID 启动的进程（已告诉执行会话）。
