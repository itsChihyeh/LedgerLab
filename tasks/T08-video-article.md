# P7-T08 演示视频与技术文章草稿（AC-9.12）

## 背景
demo-and-delivery.md 第 10 节；P7-T05 的 Playwright；`docs/handoff/p1.md`～`p6`、`docs/testing/model-tests.md`、`docs/perf/`、`docs/runs/`（如已有）作为数字来源。

## 范围
1. Playwright 录制脚本 `frontend/e2e/demo-video.spec.ts`（单独的 project，默认 `test:e2e` 不跑）：打开演示页 → 依次点 8 个动作，每个之间停 2 秒 → 停在不变量面板和计数上。命令 `npm run video:demo` 生成 webm 到 `docs/media/`；文件大于 20MB 就不提交，只在 README 写生成命令。
2. `docs/articles/break-it-ledger.md` 草稿（中文，约 2500～3500 字；另附 300 字英文摘要）：结构按第 10 节。**每个数字后面括注出处文件路径**；没有出处的数字不写。P6 真实运行和 AC-8.8 等还没完成的部分如实写“待完成”。不写成用户数，不夸大。
3. 文末“局限”一节必须包含：单商户、模拟银行、测试模式下 webhook 和结算单同源、评测数字只在模拟器模式下有真值。

## 可改范围
`frontend/e2e/demo-video.spec.ts`、`frontend/package.json`（只加脚本）、`frontend/playwright.config.*`（只加 project）、`docs/media/**`、`docs/articles/**`、README 命令列表。

## 完成标准
`npm run check` 通过；视频生成（或说明为何没提交）；文章完成。本地提交（`Task: P7-T08`）。
