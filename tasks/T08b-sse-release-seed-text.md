# P7-T08b 关页时立即释放 SSE 名额；种子提示文字与实际一致（AC-9.2/9.8 补）

## 背景
T05 报告：关页后 SSE 名额最多 15 秒才释放（每 IP 3 个连接）。T04 报告：种子实际是 4 笔支付，`up.sh`/`demo.py` 的提示文字和设计文档第 5 节写的是“3 笔已结算支付”。

## 范围
1. **前端**：页面 `pagehide`（以及 `beforeunload`）时主动 `close()` EventSource。前端改动不超过 20 行。
2. **保活间隔改 5 秒**：只改 SSE 保活的配置值，不改其他逻辑。保活配置如果在 `src/main`，只允许改这一个常量或配置项；超出就不改，在输出里说明。
3. **E2E**：加一个断言。同一 IP 打开 3 个页面后关掉 1 个，在 5 秒内能开第 4 个连接，用轮询等待，不用固定 sleep。
4. **种子**：先读 seed 实现，确认第 4 笔支付的来历，例如是否是给“1 笔已完成退款”单独建的那一笔。
   - 如果是：把 `demo.py`/`up.sh` 的提示文字和 `docs/design/demo-and-delivery.md` 第 5 节、第 13 节第 135 行附近都改成实际值，例如“3 笔已结算支付＋1 笔已退款支付”。
   - 如果是多建的错误：停下来提问，不要改 seed 逻辑。
   - 不改 seed 的行为。

## 可改范围
`frontend/**`、`scripts/demo/**`、`docs/design/demo-and-delivery.md`、SSE 保活的那一个配置值（`src/main/resources/application-demo.yaml` 或对应常量）、`src/test/**` 里与 demo 相关的测试。

## 完成标准
`cd frontend; npm run check` 通过、`npm run test:e2e` 通过，相关 Java 测试通过。本地提交，提交信息带 `Task: P7-T08b`。

## 执行者验证
全量 `verify`；`cd frontend; npm run check; npm run test:e2e`；`python scripts/demo/selftest.py`。
