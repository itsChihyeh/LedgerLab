# T09 并发、模型测试与手算样例

## 背景
ADR-0018；money-model 样例 A（全部）、B、C、F、G 与 INV；AC-1.4e、AC-7.14、AC-7.27；P4 T06 的 `PaymentModelProperties` / `CorrectionModel` 做法、P3 T13 的样例测试做法。依赖 T03–T08。

## 范围
1. AC-7.14：退款 800 与提现 1000 并发，栅栏 50 次＋两种受控交错（用资金锁前后的钩子或 latch，不用 sleep）。
2. `P5SamplesTest`：样例 A 全部逐格（第 9～11 步走真实提现 API、模拟银行与银行文件）、B、C、F、G，每步检查余额表与资产=负债。
3. 有状态模型：在现有 `PaymentModelProperties` 动作链加入 AC-7.27 列出的动作；参考模型 `PayoutModel` 只依赖 JDK 与测试参考模型；固定新种子、本地 100、CI 1000；4xx 无写入用完整快照比较；空库按场景日志重建并比较提现、历史、银行流水匹配状态。P4 时“2104 必须为零”的断言改为按模型计算。
4. money-model 第 5/6 节实现备注。

## AC / 测试场景
AC-1.4e、AC-7.14、AC-7.27。最后输出写动作次数、边界次数、序列长度和重建结果。

## 可改范围
测试代码（`src/test/**`）、必要的测试钩子（生产中为无操作）、`.github/workflows/ci.yml`（只在需要加跑新 Properties 时）；设计文档实现备注。

## 完成标准
按 `_common.md` 第 5 条（加上改动的 *Properties 类）；本地提交（`Task: T09`）。
