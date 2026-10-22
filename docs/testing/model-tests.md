# P1 应用级模型测试（AC-4.11）

`PaymentModelProperties` 用 jqwik 1.10.2 `ActionChain` 驱动完整 Spring 应用：签名 HTTP webhook → 真实 PostgreSQL inbox → 手动调用 worker。一个属性运行共用一个应用上下文和一个 Testcontainers 数据库；每次执行（包括收缩）使用独立编号前缀，并在开始时快照全部账户余额。全库审计没有按前缀过滤。P1a 的 `LedgerStatefulProperties` 保留不动。

## 动作和前置条件

| 动作 | 前置条件 | 行为 |
|---|---|---|
| 新 charge | 无 | 随机合法金额 50..99999999，含上下边界；非法金额含 49、100000000、小数、字符串、0、负数；随机改变 currency、captured、amount_captured、事件或 Charge 的 livemode |
| 同 ID 同内容 | 本序列至少已有一个事件 | 时钟前进 1 秒，以新签名重投原文 |
| 同 ID 改金额 | 本序列至少已有一个事件 | 新签名投递不同内容；原事件不变，保存冲突副本，差异按事件去重 |
| 新 ID 同事实 | 本序列至少有一个通过准入的 charge 事件（可尚未处理） | 复制 charge 与金额，换事件 ID |
| 新 ID 不同金额 | 同上 | 金额换成另一个合法值，同时改变 amount_captured |
| 未订阅类型 | 无 | customer.created |
| 拒付类型 | 无 | charge.dispute.created |
| 排空 worker | 无 | 逐批处理到没有可抢占事件，再核对模型；最多 100 批，避免挂死 |
| 抢占后崩溃 | 存在 RECEIVED 事件 | afterClaim 抛模拟进程死亡的 Error，不走普通失败退避 |
| 业务写入后崩溃 | 存在 RECEIVED 事件 | afterBusiness、epoch 写回前抛 Error，验证业务事务完整回滚 |
| 时钟前进 | 无 | 1..240 秒；后续投递按当前时钟重新签名 |

两个崩溃动作均在崩溃后立即核对模型及审计，检查 RECEIVED、attempts=0 和租约 epoch；推进 31 秒超过 30 秒租约，再创建不同 owner 的 worker 接管、排空，并检查 epoch 再增加一次。没有 sleep。

序列设置最多 24 步，结束时额外排空并检查。当前 jqwik 版本的正常生成样例实际都是 24 步，收缩会缩短动作链；报告同时记录实际平均和最大长度。事件可能积压多个动作才处理；同一接收时刻的事件使用逆序编号，因此新 ID 的同事实事件可以先于原事件处理。参考模型独立按 received_at、event_id 排序，不读取 worker 的实际选择来决定预期。

## 参考模型与检查

`PaymentModel` 只依赖 JDK，不导入生产类型，也不调用生产准入或金额方法。规则来自 money-model 第 1/3/5 节、state-machines 第 1/5 节和 stripe-integration 第 2 节：准入优先级、事件去重、事实去重、业务冲突、差异按类别和事件去重、已记 charge 金额与余额增量。

每步检查：

- `LedgerAudit.check()` 全库 INV-1～5、INV-8 无违反。
- 全部账户余额等于序列开始快照加模型变化；只有 1101/2101 增加。
- 每个事件的状态与结果，包括尚未处理的 RECEIVED，与模型完全一致。
- 支付金额与 CAPTURED 状态一致；2101 增量等于本序列 CAPTURED 金额合计（INV-9 的 P1 部分）。
- 每个 charge 的 PAYMENT_CAPTURED 分录恰好 0 或 1 条；全库分录增量等于模型新事实数（INV-10 的 P1 对应部分）。
- 差异类别计数与冲突副本数量一致。

失败执行也会清空待办，后续收缩使用新的前缀和余额快照；不修改、删除或截断已封存账本。报告的执行次数及动作次数包括失败后的收缩和重试，正常通过时执行次数等于属性条数。报告同时给出通过次数、长度、检查失败次数、清理失败次数、崩溃点、固定种子和耗时。

## 不覆盖什么

它只覆盖生成器能表达、参考模型能判断的情况，不等于并发测试；并发仍由已有栅栏和受控交错测试验证。没有覆盖未生成的 JSON 字段组合、签名攻击、进程/网络真实故障，也不替代 P1b 的专项测试。

INV-6、7、11：**不适用，P2/P3/P5 扩展时加入**。退款、结算、银行流水、提现及纠错尚不在动作集合中。AC-4.13 的场景输入日志与空库重建是独立任务，本测试不声称覆盖它。

## 运行与复现

需要 Java 25 和 Docker。PowerShell 用 `.\mvnw.cmd` 替换 `./mvnw`。

```sh
./mvnw verify
./mvnw -B -Pmodel test -Dtest=PaymentModelProperties
```

默认运行 100 条；`model` profile 运行 1000 条。CI 独立 job 每次运行 1000 条并始终上传 `target/model-report.md` 和 Surefire 报告。固定种子为 `2026102201`，使用 RANDOMIZED 生成和最多 30 秒的 BOUNDED 收缩（输出界限内找到的最短序列，不声称数学上的全局最短）。

失败后保留控制台的 `Shrunk Sample`（动作及参数）、种子、运行命令和代码版本。在同一版本上重跑原命令即可从固定种子生成相同输入；jqwik 也会用本地 `.jqwik-database` 优先重放已保存的失败样例。共享库编号前缀可能不同，不影响模型结果。收缩输出中的事件索引是相对于当前序列的已有事件，前置条件由 jqwik 随收缩重新检查。

有效性验证（2026-10-22）：临时将模型的 `DUPLICATE_FACT` 改为 `APPLIED`，运行单独属性，得到 `Tests run: 1, Failures: 1`、`BUILD FAILURE`。jqwik 在 30 秒界限内将失败链从 10 个实际动作收缩到以下 5 个动作（控制台 `Shrunk Sample (6 steps)` 中的 steps 指收缩步数）：

```text
charge amount=50 NORMAL
CRASH_AFTER_CLAIM
charge amount=1341 PARTIAL
NEW_ID_SAME_FACT event-index=0
DRAIN
```

失败指出重复事实事件实际结果为 `DUPLICATE_FACT`，错误模型预期为 `APPLIED`。模型规则随后恢复。该序列保留为验证记录；正式运行数据见交付说明。没有提交改错版本。

## 本次实测（2026-10-22）

Windows PowerShell，Java 25，Docker Desktop 的真实 PostgreSQL 18：

| 命令 | 结果 | Maven 总耗时 |
|---|---|---|
| `.\mvnw.cmd -B verify` | `Tests run: 497, Failures: 0, Errors: 0, Skipped: 0`；`BUILD SUCCESS`；含 100 条模型序列 | 02:25 min |
| `.\mvnw.cmd -B -Pmodel test -Dtest=PaymentModelProperties` | `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`；`BUILD SUCCESS`；jqwik `tries=1000, checks=1000` | 05:37 min |

1000 条报告：种子 `2026102201`，24,000 个生成动作，平均/最大长度 24/24，28,656 个检查点，违反次数 0，清理失败 0。属性含启动耗时 332.773 秒；afterClaim 崩溃 1,295 次，afterBusiness 崩溃 1,361 次，所有动作类别均有覆盖。Surefire 将整个属性计为 1 个测试，序列条数以 jqwik 和 `target/model-report.md` 为准。
