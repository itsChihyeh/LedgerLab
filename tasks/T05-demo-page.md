# P7-T05 演示页与 Playwright（AC-9.1、AC-9.2、AC-9.7）

## 背景
demo-and-delivery.md 第 2、6、7 节；现有前端（`frontend/src/*`、`frontend/e2e/*`、`playwright.config`）；P7-T02 的接口。

## 范围
1. 新页面 `#/demo`（demo profile 下作为首页）：8 个动作按钮（每个带一句话说明“它在证明什么”）、事件时间线（SSE）、不变量面板（12 条，失败时红色并显示违规明细和对象编号）、计数卡片、上次重置时间与 7 天存档、真实沙箱报告面板（第 7 节：构建时复制 `docs/runs/` 最新 `sandbox-*.json`；没有就显示“尚无运行报告”）。中文界面，和现有页面同一风格；手机宽度可用。
2. 不设 cookie、不接第三方脚本；加载时 `POST /api/demo/visit` 一次。
3. AC-9.2 的故障开关：test profile 专用 `ledgerlab.test.break-refund-lock`（第 6 节），并加单元测试断言 demo/default 上下文里不存在该 bean。
4. Playwright：`ac_9_1_everyActionUpdatesTimelineAndInvariants`（依次点 8 个按钮，每个 2 秒内时间线出现新条目、面板全绿、对应计数增加）；`ac_9_2_brokenLockShowsInv7Failure`（用 break-refund-lock 配置起后端，点 double-refund，面板显示 INV-7 失败和支付编号）；`ac_9_7_sandboxReportPanel`（有/无报告文件两种）。沿用现有 E2E 启动方式（自动起测试后端和 Vite）。

## 审查补充（2026-11-05）
见 `answers/T02-T03-review.md`：计数卡片区分“账本查询”和“演示动作观察到的”；kill-worker 的说明按“重新领取租约（代数 +1）”写；E2E 断言内部令牌头的值不出现在页面拿到的任何响应里。

## 可改范围
`frontend/**`、`src/main/java/dev/ledgerlab/**`（只限 test profile 的故障开关和它的测试）、`docs/design/demo-and-delivery.md`。

## 完成标准
按 `_common.md` 第 5 条，并跑 `cd frontend && npm run check && npm run test:e2e`。本地提交（`Task: P7-T05`）。

## 执行者验证
全量 verify 外，亲自跑 `cd frontend; npm run check; npm run test:e2e`。
