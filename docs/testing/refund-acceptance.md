# P3 T04 退款受理、资金队列与取消测试

所有业务场景使用 Testcontainers 的真实 PostgreSQL，迁移后以 app 角色调用生产服务。S1/S3、后续资金增加均通过既有 PaymentCapture、BatchSettlement、BankStatements 构造，不直接伪造账户余额。没有 HTTP、真实 Stripe 或模拟器出站调用；命令数量验证属于本任务的提交边界。

## 确定性场景

`RefundServiceTest` 覆盖 AC-3.2～3.7、3.9、3.12～3.14、3.16 的领域部分：并发 6000/6000、5000/5000、已有 2000 后并发、超额拒绝、未结算支付退款、FIFO 不插队、取消队首后放行、READY 取消拒绝、插入后异常全回滚、1 分与上限及非法金额、51+49。两线程使用 CyclicBarrier，读取 Future 有超时，无 sleep。

AC-3.3、3.9 同时验证加入调用方事务：在事务里观察放行和命令后主动回滚，再验证退款状态、余额、命令一起恢复，随后正式提交。重复 release 不创建第二条命令；无事务或无资金锁调用 release/assertFundsLocked 必须失败，锁标记在事务结束后消失。

每个正常用例结束通过 `RefundAssertions` 检查账本 INV-1～5/8、支付退款合计及缓存的 INV-7、2103 与等待／READY／SUBMITTED／UNKNOWN 退款合计的 INV-9；所有退款分录不得触碰 1103（AC-3.14），该辅助类供后续任务复用。

## AC-3.13 锁必要性的变异测试

包内 `RefundLimitGuard` 是可替换的额度协作者。生产实现先取资金锁，锁支付行，再检查并用 `refunded_amount = refunded_amount + :amount` 预留；生产构造器没有故障参数或配置开关。

测试故障实现不取资金锁、不做 SELECT FOR UPDATE，普通读取 refunded_amount 后在栅栏让两个事务都读到 0，再分别写回各自的旧值 + 6000。测试中的账本适配器只在记账时补取资金锁，保证分录完整而不保护已经执行的额度读写。最终两笔退款均受理、合计 12000，支付的 refunded_amount 却是 6000，INV-7 检查必须抛出 AssertionError；账本平衡与退款不碰现金仍通过。同一个测试再以生产实现运行同一并发输入，必须恰好一笔成功、一笔 REFUND_EXCEEDS_REMAINING，全部不变量通过。

这里“故障版本失败”指不变量校验确定失败，测试通过意味着成功抓到超退。数据库 `refunded_amount <= amount` CHECK 只是第二道防线：两次写回均为 6000，不能发现真实退款合计 12000。故障实现仅位于测试代码。

## jqwik 独立参考模型

`RefundStatefulProperties` 使用 ActionChain，固定种子 `2026102404`，每条最多 12 步；默认 100 条，model profile 1000 条。CI 的 model 作业同时选择既有 PaymentModelProperties 与本属性。

动作：ACCEPT（金额超过剩余额时验证拒绝）；CANCEL（已有退款才选取，非等待状态验证拒绝）；SETTLE（首次结算原支付净额 1500，后续新增支付并结算净额 1000）；RELEASE（持资金锁重复放行）。每步推进注入 Clock 一秒，比较全部账户、退款状态、额度、队列顺序、命令与分录数、不可变首次状态，并检查上述不变量。

参考模型 `RefundReference` 仅依赖 JDK，以“已结算但尚未分配的资金池”逐笔分配，独立于业务代码和 SQL。该生成器覆盖受理、取消与结算放行，不覆盖后续执行、失败、处理方超时或 HTTP 幂等；崩溃点为确定性用例里的退款插入后，随机动作未包含崩溃。随机测试不代替栅栏并发测试。

## 运行

```powershell
.\mvnw.cmd -B spotless:apply
.\mvnw.cmd -B test '-Dtest=RefundServiceTest,RefundStatefulProperties,ModularityTests'
.\mvnw.cmd -B -Pmodel test '-Dtest=RefundStatefulProperties'
.\mvnw.cmd -B verify
```

本次修复先运行首次响应回归用例，复现 `permission denied for sequence refund_queue_seq_seq`（1 个测试、1 个错误）。实现改用省略 identity 的 INSERT RETURNING，不改 V15、不加 V16；首次响应精确比较六个字段且不含 queue_seq。

2026-10-24 定向运行 `spotless:apply test '-Dtest=RefundServiceTest,RefundStatefulProperties,ModularityTests'`：20 个测试，0 失败、0 错误、0 跳过，BUILD SUCCESS（04:45 min）。其中退款领域 18 个用例，Modulith 1 个，jqwik 属性 1 个；属性实际执行 tries=100/checks=100，种子 2026102404，耗时 235.4 秒。本地未额外运行 1000 条，CI 的配置使用 model profile 执行 1000 条；不将配置当作实测结果。

同日完整运行 `.\mvnw.cmd -B verify`：1111 个测试，0 失败、0 错误、0 跳过，BUILD SUCCESS（25:32 min）。包括 Modulith、全部既有随机模型、新退款模型（再次 tries=100/checks=100，235.5 秒）、JaCoCo 覆盖率门槛、Spotless 和打包；没有跳过或删除测试。
