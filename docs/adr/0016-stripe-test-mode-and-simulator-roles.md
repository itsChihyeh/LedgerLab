# ADR-0016 接入 Stripe 测试模式；模拟器说 Stripe 的话

状态：已采纳（2026-10-21，P0）。来源：交接说明第 14 节第 1 项（用户 10-21 确认）。细节见 design/stripe-integration.md。

## 背景
对照 37 份加拿大 CS 学生简历，LedgerLab 的短板是没有真实用户和上线数据。处理方一侧全是我们自己编的，可信度有限。用户要求支付事件改由 Stripe 测试模式的真实 webhook 进入，对账的外部数据改用 Stripe balance transactions / payout。原设计里模拟器负责产出真值和故障注入，接入以后两者要分工。

## 决定
1. **处理方就是 Stripe。**一个结算批次 = 一笔 Stripe payout；批次行 = 该 payout 的 balance transactions。资金模型随之简化：处理方余额 ≤ 0 时不产生批次，没有“负净额结转批次”（ADR-0001 修订）。
2. **只有一个处理方适配器**：Stripe webhook 入口（Stripe 签名方案）＋ Stripe REST 客户端。真实 Stripe 和模拟器共用它。
3. **模拟器的处理方部分改成 fake Stripe**：实现我们用到的 Stripe 协议子集（事件格式、签名、退款与查询、balance transactions、payouts 接口）。它继续负责确定性场景、故障计划和真值，是 CI、模型测试、对账准确率评测和公开演示页的处理方。
4. **故障注入从模拟器里拆出一个 chaos relay**，挡在 webhook 入口和出站调用前面。两种模式都能用它注入重复、延迟、乱序、丢弃和丢回执。
5. **银行一直是模拟的。**真实沙箱模式下，模拟银行把 Stripe payout 当成收到的转账，自己生成入账流水。
6. **真实沙箱是一个运行模式，不是测试替身。**它不进每次 CI；按需运行，产出运行报告。CI 里用录制的真实 Stripe 夹具做契约测试，保证 fake Stripe 不漂移。
7. 密钥只进环境变量和密钥管理服务，只接受测试模式密钥；`livemode=true` 的事件拒绝。

## 备选
- **两个适配器（自定义协议的模拟器＋Stripe 适配器）**：模拟器更自由，但生产路径有两套解析和映射，模拟器测过的那套不是线上那套。否决。
- **只接 Stripe、去掉模拟器**：失去确定性故障注入和真值，对账准确率无法评测，CI 依赖外网和密钥。否决。
- **Stripe Connect（商户作为连接账户）**：能用 Stripe 模拟给商户打款，但模型变成多账户平台，范围大增；我们的商户提现仍走模拟银行。暂不做。

## 后果
- 模拟器要跟着 Stripe 的字段走，范围限定在我们订阅的事件和调用的接口。
- Stripe 幂等键会在 24 小时后过期，出站重发必须“先查后发”（ADR-0008 补充）。
- 测试模式下“结算单”和 webhook 同源，独立性较弱，评测数字只在模拟器模式下报。
- 简历可以写“integrated with Stripe test mode”，但数字只能来自运行报告。

## 补充（2026-10-21，开工前审查）
- 第 1 条“处理方余额 ≤ 0 时不产生批次”不是 Stripe 的行为，见 ADR-0001 补充。
- 后果里“幂等键会在 24 小时后过期”改为：Stripe 的幂等键**至少保留 24 小时**，之后可能被清理。
- Stripe API 版本、SDK 版本和事件格式在 P1 开工前固定（design/stripe-integration.md 第 8 节），不等到 P6。
- fake Stripe 要按 Stripe 的规则维护可用余额（退款不够时 pending）和 payout 明细就绪（`reconciliation_status`），否则模拟器测过的行为和真实 Stripe 不一致。
