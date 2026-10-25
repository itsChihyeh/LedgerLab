# P1/P2/P3 应用级模型测试（AC-4.11、AC-4.13）

`PaymentModelProperties` 使用 jqwik 1.10.2 `ActionChain` 驱动完整 Spring 应用：签名 HTTP webhook → 真实 PostgreSQL inbox → 手动 worker；P2 再接本地 HTTP 处理方分页及银行 CSV 导入。每条序列都使用独立空库、应用上下文和同种子编号器，只共用 Testcontainers PostgreSQL 18 容器进程；每第 10 条在另一个空库重建。没有数据库 mock，没有真实 Stripe 调用。P1a 的 `LedgerStatefulProperties` 和 P2 已有专项模型保持不变。


## P3 / T13 扩展

P3 保留每条最多 24 个生成动作、默认 100 条、`-Pmodel` 1000 条及每 10 条一次空库重建，CI 配置不变。P1/P2 动作表仍适用，另加下表；所有前置条件由独立模型判断。

| 动作 | 前置条件 | 行为 |
|---|---|---|
| ACCEPT_REFUND | 已处理至少一笔支付 | 真实 POST `/api/refunds`；随机正金额、全部剩余额度、超额及剩余为零；检查 201/409/400 与额度不变性 |
| CANCEL_REFUND | 存在 AWAITING_FUNDS | 带幂等键与原因调用取消 API，检查释放额度、分录和 FIFO |
| COMMAND | 存在未完成命令 | 一轮真实出站 worker，脚本网关返回 succeeded、pending、failed、4xx、创建前超时、创建后丢响应或成功 webhook 先到再超时；推进 Clock 超过退避上限 |
| PROCESSOR_AVAILABLE | 存在处理方 pending 的 SUBMITTED 退款 | 脚本提供余额恢复后的成功 webhook，检查待付清零、应收减少与现金不变 |
| REFUND_WEBHOOK | 已有处理方退款身份 | created/updated/failed 三种事件，重复状态以及终态之后迟到的 pending；事件名不代替对象 status |
| REPLAY_REFUND | 已投递退款事件 | 相同原文、相同 event_id，重新签名投递 |
| RECOVER_EXPIRED | 存在未完成命令 | 推进 25 小时再恢复；按 re_ 查或完整列表查询，找到就不重发，完整未找到才重发同 request_id |
| API_REPLAY | 已有受理成功响应 | 同键重放，或推进 25 小时调用真实缓存清理后以新键、原业务编号重放；响应必须等于首次结果 |
| REFUND_BATCH | 有已知 re_、未入批的 SUBMITTED/UNKNOWN/SUCCEEDED 退款，及足以组成正净额批次的未分配支付 | 真实分页拉取混合 charge/refund 批次；独立核算负退款行、净额、首次成功及跨证据去重 |

`RefundModel` 独立实现剩余可退额、最长 FIFO 前缀、状态和 2102/2103/1101 分录；不导入业务类，不读库。初始余额来自该序列空库基线，之后只加独立模型增量。退款日志包含本地编号，所以不能沿用 P2 的共享应用编号器；独立空库也让收缩不依赖上一条序列的可用款，代价是更多应用启动和迁移。每步比较全部账户、退款状态/金额/处理方身份/批次、支付退款额度、不重复的命令及其终态、退款分录和 posting 多重集合；另运行全库 INV-7、INV-9（2103）、INV-10、AC-3.14。失败样本不使用已被推翻的 oracle 清理业务状态，而关闭并丢弃该测试库，以新库继续收缩。

网关替身保存每次调用的原始 JSON 或超时标记，操作类型必须匹配，CREATE 的 request_id 必须等于本地退款号。完整列表回执包括两页、首尾 has_more 和无关退款，匹配项在末页；未完成页返回 Incomplete。它验证命令层的完整查询契约，真实 HTTP 分页、请求形状和网络故障由 T08 专项测试负责。处理方余额不足以 pending 回执、随后资金可用的 succeeded webhook 表达；不是模拟器全量可用余额算法的证明。早到 webhook 在网关返回之前同步投到真实 inbox，不假装多线程并发。

`ScenarioLog` 新增 API 原文、幂等键、响应结果、出站调用的有序回执、早到 webhook 的原文/签名和缓存清理动作；所有输入携带 Clock，超时也在日志内。重放执行相同 API 和 worker，并逐个核对 API 响应。结尾先取消仍在等待的退款，再通过脚本恢复未完成命令及 pending 退款；这些收尾也是被记录的外部输入。原库和重建库均断言 inbox 无 RECEIVED/PARKED，命令全部 DONE。快照按处理方支付号＋商户退款编号比较退款、顺序历史、命令终态和 API 幂等结果，并继续比较原有全部对象与分录。

`P3SamplesTest` 经签名 webhook、批次 HTTP 替身、银行 CSV、退款 HTTP API 和真实命令 worker，逐步核对样例 A 1–8（包括 2′ 的余额不变）、D 的全部账户、资产负债合计、每条分录类型和 posting 多重集合。D 的 MA=5000 前置由请款 5180、手续费 180、批次和银行入账建立；这是样例未指定初始资产分布的测试夹具选择。`P3ScenarioTest` 定向核对 FIFO 不插队、取消、退款入批、各种回执和过期恢复，并在空库重放。

### T13 实现文件与验证范围

新增 `model/P3SamplesTest`、`P3Scenario`、`P3ScenarioTest`、`RefundModel`、`RefundReplay`，以及 `refunds/internal/ModelIdempotency` 测试接线。扩展 `PaymentModelProperties`、`ModelApplication`、`ModelReport`、`ScenarioLog`、`RebuildSnapshot`、`ScenarioRebuildTest`、`P2Scenario`、`SettlementModel` 和共享 `RefundAssertions`。场景导出/重放文件与真值清单见 [scenario-fixtures.md](scenario-fixtures.md)。未修改主应用生产代码、已提交迁移、运行时/测试依赖、CI 配置或前端。

开发验证曾发现新场景导出 manifest 的 POST form 字段顺序随 JVM 的不可变 Map 遍历顺序变化；改用 TreeMap 后重新导出，并通过完整模拟器 verify（122 个测试，0 失败、0 错误、0 跳过）。故意改错实验还触发了测试应用重复关闭时重复 DROP DATABASE 的清理错误；测试关闭操作改为幂等，并添加 AC-4.13 回归测试。两者均属于本任务测试/场景支持代码，未发现需要修改主应用生产代码的问题。

## 动作和前置条件

| 动作 | 前置条件 | 行为 |
|---|---|---|
| 新 charge | 无 | 合法金额 50..99999999（含边界）；非法金额、币种、部分请款、未请款、livemode 变体 |
| 同 ID 同内容／改金额 | 已有 charge 输入 | 前进 1 秒重新签名，分别检查去重／冲突存档 |
| 新 ID 同事实／改金额 | 至少一个通过准入的 charge 输入，可尚未处理 | 更换 event_id；检查事实去重或业务冲突 |
| 未订阅／拒付事件 | 无 | customer.created／charge.dispute.created |
| 排空 worker | 无 | 最多 100 批，避免回归挂死 |
| 抢占后／业务写入后崩溃 | 有 RECEIVED charge | Error 注入 afterClaim／afterBusiness；立即检查，再前进 31 秒换 owner 接管 |
| 秒级时钟前进 | 无 | 1..240 秒，后续 webhook 用新签名 |
| CREATE_BATCH | 有已请款且尚未分配到模型批次的支付 | 打包 1..3 笔支付；实际手续费取合同值、合同值+1 或 0；随机发送 created，或暂不发送以制造乱序 |
| READY | 存在尚未就绪的外部批次（本地 ABSENT／ANNOUNCED） | 全量分页后确认；若就绪先到，单独检查后再投迟到 created |
| REPLAY_PAYOUT | 已投过 payout 事件 | 原文、同 event_id，新签名再投，不重新应用业务 |
| NEW_ID_READY | 已确认、已入账或已失败批次 | 新 event_id、同批次内容，再拉完整明细，DUPLICATE_FACT |
| INTERRUPT_FETCH | 本地 ABSENT／ANNOUNCED 批次 | 第一页 200、第二页 503；检查 complete=false、没有部分版本或资金效果；恢复原文分页并推进重试 |
| FAIL | CONFIRMED／BANKED／PAYOUT_FAILED | 首次失败记 BATCH_PAYOUT_FAILED；已失败去重；BANKED 只开状态冲突 |
| REISSUE | 失败批次尚未被其他批次引用退回 | 新批次仅含一条 PAYOUT_FAILURE，G=F=0，检查恰好 2 条 posting、可用款不再增加（样例 H） |
| BANK_EXACT／BANK_SHORT | 有外部批次，允许尚未 created | 新 bank_txn_id，金额等于 net／少 1 分；同批次再次真实打款用另一个编号，转待查收款 |
| BANK_PENDING／BOOK_PENDING | 有批次／已有 PENDING 行 | PENDING 只存档；同 bank_txn_id 的 BOOKED 版本保留全部其他字段 |
| BANK_DUPLICATE_FILE | 已导入文件 | 原始文件字节重导，不新增流水版本或 posting |
| BANK_UNKNOWN | 无 | 引用未知 po_ 的 BOOKED 入账，进入 WAITING |
| ADVANCE_DAYS | 无 | 前进 1 或 7 个 24 小时并调用 WAITING 扫描；独立模型按多伦多工作日到期 |

每条最多 24 个生成动作，结束时额外排空并推进 7 天清理 WAITING，再核对、重建。P2 动作开始前排空已有 charge；payout 投递在动作内同步排空，分页中断后先检查未完成状态，再恢复重试。这保证 P1 的两个崩溃动作仍针对 charge，P2 拉取故障由独立动作覆盖，不声称覆盖所有 P2 崩溃交错。定向用例也在每个操作后检查。

同一接收时刻的 charge 使用逆序事件编号，模型独立按 received_at、event_id 决定处理顺序。每次执行（含收缩）有新编号前缀和全部账户余额基线；全库不变量审计不按前缀过滤，不删除或修改封存分录。每条独立序列设置场景起始 Clock 为 2026-10-30 或 2026-11-10，避免 1000 条序列累计推进越过日历覆盖年份；单条场景内部始终只向前推进，完整时钟值保存在日志中。两个起点覆盖跨周末、夏令时结束与 11-11 非工作日。

## 独立参考模型与每步检查

`PaymentModel`、`SettlementModel` 与 `RefundModel` 均只依赖 JDK 和彼此，不导入生产类型，不读取数据库、不调用生产手续费、日历或记账方法。规则来自 money-model 第 1/3/5/6 节、contracts 第 1/3/4 节和状态机。HTTP/CSV 构造、实际应用调用、数据库断言位于 `P2Scenario`，不进入 oracle。

- 保留全库 `LedgerAudit.check()` 的 INV-1～5、INV-8 审计；全部账户余额必须等于序列基线加模型精确增量。
- 检查每个 inbox 状态／结果（含未处理与中断重试）、支付金额、CAPTURED／SETTLED、batch_id、actual_fee；检查事件冲突副本和差异。
- 独立计算批次 G、F、失败退回、N；比较批次状态、五项合计、全部已确认明细。按 source_key 比较所有 P2 分录类型及 posting 多重集合，保留重复次数，省略 0 元。全库分录数增量等于模型事实数。
- 比较每个银行流水版本、匹配结果、matched_batch_id 和不可变 wait_until；差异比较 category/subject_type/subject_id 集合。
- INV-5：显式检查 1102、1103、2105 非负。
- INV-9：全库 2101 = 全部 CAPTURED 支付毛额，1102 = 全部 CONFIRMED 批次净额。
- INV-11：全库 1103 = 按 bank_txn_id 取最高版本后 BOOKED 入账合计 − WAITING 合计，包含待查收款，重复文件／PENDING 旧版本不重复计入。所有生成银行行均为 CAD CREDIT。
- INV-10 保留请款 source_key 恰好一次的检查。P3 加入 INV-7、2103 的 INV-9、退款 INV-10 和 AC-3.14；提现 INV-6 留给 P5。

所有金额计算使用 long 与精确加减乘。随机批次净额限制为不超过 99999999，以便同一批次能生成合法的一行银行入账和失败退回行；这仅是生成域限制，不改变生产汇总金额上限。日历 oracle 独立列出场景可能跨越的合同假日（2026-10 至 2027-04），不调用生产 BusinessCalendar 或读取它的 CSV。

`P2ScenarioTest` 定向覆盖：三笔支付、两笔早到真实入账在确认时分别 MATCHED／UNAPPLIED；样例 H 的 9680 失败与重打、分页中断；就绪先于 created、PENDING→BOOKED、未知引用到期。另验证先收到 A 的 PENDING、再收到 B 的 BOOKED、最后收到 A 的 BOOKED 时，必须按 BOOKED 版本的导入顺序先匹配 B；oracle 保存全部版本的输入顺序，不按 bank_txn_id 首次出现顺序匹配。每个场景同时在空库重放，防止随机生成尚未选到某组合。

模型不是并发证明；并发和事务中断仍由已有栅栏／受控交错测试负责。不覆盖提现、纠错、未知 JSON 组合、真实网络故障或外币兑换；P2 对账运行／发现处置由 T10/T11 专项测试覆盖，本动作集检查入口差异，不执行新的对账运行。银行文件为合成的单行平衡 CSV，用于逐笔导入测试，不用于宣称完整期间报表对账已通过。

## 场景输入日志与空库重建（AC-4.13）

`ScenarioLog` 在操作前追加输入，独立于模型与数据库；不从 inbox 或账本反推出输入。失败报告保留完整日志，二进制输入以 Base64 表示。每条序列开始清空日志，头部保存 IdGenerator 种子 `2026102202`。

| 类型 | 保存与重放 |
|---|---|
| Webhook | 原始请求字节、防御性副本、原 Stripe-Signature、时钟；不重新签名或构造 JSON |
| ProcessorRest | payout 外部编号、原始第一页／末页响应文本、第二页中断开关、时钟；在仅监听本地的 HTTP 服务安装原文，通过真实生产 REST 客户端重新拉取、存档 |
| BankFile | 原始 CSV 字节的防御性副本、时钟；重新调用实际 BankStatements 导入入口 |
| BankScan | WAITING 扫描及其时钟 |
| ClockAdvance | 推进前后时刻，包括签名、租约恢复、拉取退避和等待到期 |
| WorkerRun | 时钟、owner、NONE／AFTER_CLAIM／AFTER_BUSINESS；含最后排空 |

每第 10 条做一次重建：默认 100 条中 10 次，`-Pmodel` 的 1000 条中 100 次。原始和重建各自用未迁移空模板创建数据库、运行全部 Flyway 迁移、独立 Spring 上下文／HTTP 端口／数据源／处理方服务；只复用 PostgreSQL 容器进程。不复制业务表、不清空账本。两边 IdGenerator 从同种子开始。重放单线程恢复每个输入的时钟，依次投递或执行，最后排空并断言无 RECEIVED／PARKED，关闭上下文后删除测试数据库。

`RebuildSnapshot` 全库比较：全部账户余额；按 processor_payment_ref 的支付金额、状态、batch_id、actual_fee；按 event_id 的 inbox 结果；按 source_key 的分录／posting 多重集合；按 batch_id 的状态、金额、日期、hold_reason；按 balance_txn_id 的批次明细；按 bank_txn_id/version 的银行原始业务字段、匹配状态和等待截止；差异身份及处置状态。全部差异一次报告，集合报告两边独有项。

不直接以本地编号对齐对象，不比较事务号、序列号、recorded_at、账户版本、租约或尝试次数。退款 source_key、历史证据引用和首次 API 响应含本地编号；同种子、相同调用顺序下逐字比较这些内容。P2 所生成 issue 的 subject 都是外部 event/batch/bank_txn 身份，不依赖本地 ID。分页存档的自增 ID 不参与业务比较。首次重建失败后，后续重试／收缩全部重建，避免抽样漏掉失败样例。

`ScenarioRebuildTest` 另检查日志原文所有权、尾部事件排空，以及包括批次／明细／银行行在内的完整诊断和 posting 重复次数。定向用例不计入随机运行报告。

## 运行与复现

需要 Java 25 和 Docker；Windows PowerShell：

```powershell
.\mvnw.cmd -B spotless:apply
.\mvnw.cmd -B verify
.\mvnw.cmd -B -Pmodel test '-Dtest=PaymentModelProperties'
```

Git Bash 对应 `./mvnw`。默认 100 条，model profile 1000 条，CI 配置和条数均不变。固定动作种子 `2026102201`，RANDOMIZED，BOUNDED 收缩最多 30 秒。正常生成都是 24 步，收缩可以更短；不声称找到数学意义的全局最短反例。失败复现保留命令、代码版本、种子、Shrunk Sample 和 `target/model-report.md`；jqwik 也会优先尝试 `.jqwik-database` 中的已保存失败样例。

报告记录序列数、动作数、平均／最大长度、检查点、违反数、清理失败、重建一致数、种子、耗时，各动作执行数，以及多支付批次、实际手续费差异、零费用、ready 先到和银行匹配类别。失败运行的计数含收缩／重试，不能当作独立随机样本数。

## T13 实测（2026-10-24）

Windows PowerShell、Java 25、Docker Desktop / PostgreSQL 18；使用正确 FIFO 模型和每条序列独立空库的最终代码。

| 命令 | 结果 | Maven 总耗时 |
|---|---|---|
| `.\mvnw.cmd -B spotless:apply verify` | 1366 个测试，0 失败、0 错误、0 跳过；模块边界、覆盖率、Spotless、打包通过；BUILD SUCCESS | 34:18 min |
| `.\mvnw.cmd -B -Pmodel test '-Dtest=PaymentModelProperties'` | jqwik tries=1000/checks=1000；Surefire 1 个属性测试，0 失败、0 错误、0 跳过；BUILD SUCCESS | 23:41 min |
| `.\mvnw.cmd -B -f simulator/pom.xml verify` | 122 个测试，0 失败、0 错误、0 跳过；BUILD SUCCESS | 01:25 min |
| `.\mvnw.cmd -B spotless:apply test '-Dtest=PaymentModelProperties,P3ScenarioTest,ScenarioRebuildTest,P3SamplesTest'` | 18 个测试全绿；随机模型 100 条全部通过 | 03:51 min |

完整 verify 内 P3 应用模型：100 条、2400 个生成动作、2825 个检查点、0 次违反、0 次清理失败，10 次重建全部一致；模型耗时 173.721 秒。报告另存本机 `target/t13-model-verify.md`。全量日志出现既有缓存测试上下文连接池尝试连接已删除测试库的 WARN，但所有测试与构建检查均通过，未因此扩大修改范围。

1000 条正式实测：**24000 个生成动作、28267 个检查点、0 次违反、0 次清理失败，100 次重建全部一致**。平均／最大动作长度 24／24；模型含应用启动耗时 1414.608 秒。动作种子 `2026102201`、编号种子 `2026102202`；afterClaim 668 次、afterBusiness 623 次。原始报告另存本机 `target/t13-model-1000.md`，命令输出为 `target/t13-model-1000.log`，CI 条数和配置未改。

| P3 动作 | 1000 条中的执行次数 |
|---|---:|
| ACCEPT_REFUND | 1834 |
| CANCEL_REFUND | 255 |
| COMMAND | 101 |
| REFUND_WEBHOOK | 26 |
| REPLAY_REFUND | 4 |
| RECOVER_EXPIRED | 54 |
| API_REPLAY | 245 |
| PROCESSOR_AVAILABLE | 1 |
| REFUND_BATCH | 1 |

特征计数含收尾动作、可重叠：COMMAND_RESULT_0..7 分别为 320／9／13／11／13／18／20／22；完整查询未完成 5 次、webhook 先于响应 20 次、零剩余拒绝 322 次、超额拒绝 348 次、受理即 READY 174 次、受理等待资金 969 次。资金恢复和退款批次在该固定种子随机运行中各仅 1 次，不能据此声称广泛组合覆盖；定向 `P3ScenarioTest` 与手算／场景真值测试另行验证这些路径。网关回执计数含收尾成功恢复，所以不等于 COMMAND 生成动作数。

## T13 有效性演示（2026-10-24）

在每条序列独立空库的最终框架中，临时将 `RefundModel.release()` 的 `if (r.amount > capacity) break;` 改为 `continue;`，使模型错误地跳过资金不足的队首。只改参考模型，未改生产代码。运行 `.\mvnw.cmd -B test '-Dtest=PaymentModelProperties'`，第 4 条随机序列失败（tries=4/checks=4），Surefire 1 个测试、1 失败、0 错误、0 跳过，Maven `BUILD FAILURE`、01:28 min。

断言为后续退款模型预计 READY、数据库实际 AWAITING_FUNDS，直接定位 AC-3.7 的不插队规则。30 秒 BOUNDED 收缩显示 `Shrunk Sample (5 steps)`：这是 5 次收缩步骤，最终链为 17 个动作（原生成上限 24，原失败也发生于第 17 步），并将第三次退款选择索引从 4 收缩到 0。完整反例如下；这是本次有界搜索找到的反例，不声称数学意义的全局最短。

```text
UNSUBSCRIBED
DRAIN
advance 6s
charge amount=100000000 NORMAL
charge amount=55 NORMAL
DRAIN
CREATE_BATCH index=6 variant=true
ACCEPT_REFUND index=148 variant=3
charge amount=77 NOT_CAPTURED
NEW_ID_SAME_FACT event-index=0
NEW_ID_SAME_FACT event-index=0
ACCEPT_REFUND index=4 variant=5
SAME_ID_SAME_BODY event-index=4
charge amount=50 NORMAL
ACCEPT_REFUND index=0 variant=1
NEW_ID_SAME_FACT event-index=1
READY index=5 variant=false
```

含收缩共 21 次执行、366 个动作、438 个检查点、6 次违反、0 次清理失败；这些不是正式通过率。完整原文输入和 API/网关回执保存在本机 `target/t13-mutation-isolated-report.md`，控制台在 `target/t13-mutation-isolated.log`。已恢复正确的 `break`，故障代码未提交。早期共享应用框架的实验及主动中止的 FULL 收缩试跑不作为本次验收结果；独立空库版本的正式实测另记。

## T15 有效性演示（2026-10-23）

临时把 `SettlementModel.post` 中省略 0 元的 `.filter(p -> p.amount() != 0)` 改成 `.filter(p -> true)`，只改 oracle，生产代码不变。运行 `.\mvnw.cmd -B test '-Dtest=PaymentModelProperties'`：`Tests run: 1, Failures: 1, Errors: 0, Skipped: 0`、`BUILD FAILURE`，Maven 01:18 min，jqwik `tries=4/checks=4`，在第 4 条随机序列发现错误。

30 秒有界收缩报告 `Shrunk Sample (14 steps)`，24 个最大动作收缩为下列 8 个动作；原场景也是执行到第 8 步失败，收缩另外把非法 charge 金额 22077 降到 50、重复事件索引降到 0、拉取索引 6 降到 0。这里的 14 steps 是收缩步骤数，不是最终动作数，不能声称 8 步是全局最短。

```text
DRAIN
charge amount=50 CHARGE_LIVE
charge amount=33026 CHARGE_LIVE
charge amount=66 NORMAL
DRAIN
SAME_ID_SAME_BODY event-index=0
CREATE_BATCH index=28 variant=false
INTERRUPT_FETCH index=0 variant=false
```

失败定位到 `posting multiset batch:po_…:settled`：数据库实际 4 条非零 posting，错误模型要求额外的 `Posting[account=2101, side=D, amount=0]`。本次含收缩共 67 次执行、687 个检查点、15 次模型违反；这些不是正式通过率数字。已恢复正确过滤条件；故障代码未提交。正常版本的最终全量与 1000 条实测列在下方交付记录。

## T15 实测（2026-10-23）

Windows PowerShell，Java 25，Docker Desktop / PostgreSQL 18.6；最终模型已修正 BOOKED 版本的导入顺序。以下均为恢复正确模型后的实际结果，故意改错的失败另见上节。

| 命令 | 结果 | Maven 总耗时 |
|---|---|---|
| `.\mvnw.cmd -B verify` | 1009 个测试，0 失败、0 错误、0 跳过；覆盖率、模块边界、Spotless、打包通过；`BUILD SUCCESS` | 28:09 min |
| `.\mvnw.cmd -B test '-Dtest=P2ScenarioTest,ScenarioRebuildTest'` | 6 个定向用例，0 失败、0 错误、0 跳过；`BUILD SUCCESS` | 58.607 s |
| `.\mvnw.cmd -B -Pmodel test '-Dtest=PaymentModelProperties'` | jqwik `tries=1000/checks=1000`；Surefire 计为 1 个属性测试，0 失败、0 错误、0 跳过；`BUILD SUCCESS` | 17:54 min |

最终 verify 内的应用模型运行 100 条、2,400 个动作、2,781 个检查点，模型违反与清理失败均为 0；10 次重建全部一致，属性含启动耗时 87.872 秒。

1000 条共 **24,000 个生成动作、27,835 个检查点、0 次违反、0 次清理失败**，平均／最大动作长度 24／24；**100 次重建，100 次一致，0 次失败**。属性含启动耗时 1067.493 秒；afterClaim 683 次、afterBusiness 705 次。动作种子 `2026102201`，编号种子 `2026102202`。1000 条报告另存本机 `target/t15-model-1000.md`，避免最后默认 verify 覆盖；CI 上传规则和条数未改。

| 新动作 | 1000 条中的执行次数 |
|---|---:|
| CREATE_BATCH | 742 |
| READY | 342 |
| REPLAY_PAYOUT | 236 |
| NEW_ID_READY | 165 |
| INTERRUPT_FETCH | 198 |
| FAIL | 339 |
| REISSUE | 59 |
| BANK_EXACT | 575 |
| BANK_SHORT | 294 |
| BANK_PENDING | 307 |
| BOOK_PENDING | 58 |
| BANK_DUPLICATE_FILE | 779 |
| BANK_UNKNOWN | 1011 |
| ADVANCE_DAYS | 1972 |

特征计数（可重叠）：多支付批次 109、实际手续费与合同不同 514、零费用批次 267、就绪先于 created 249。导入后为 WAITING／MATCHED／UNAPPLIED／IGNORED_PENDING 的次数分别为 1369／151／418／307；这些是导入检查时的状态次数，不含确认时唤醒产生的额外状态变化。

开发期先修正了测试 CSV 的 CRLF 分隔，再通过独立 JDK 复现检查修正模型的 BOOKED 导入顺序。一次使用修正前已编译测试类的 verify 被主动结束，未计作验收通过；以上 1000 条及最终全量验收均使用修正后的代码。没有发现需修改生产代码的缺陷。

实现文件：新增 `P2Scenario.java`、`P2ScenarioTest.java`、`ProcessorReplay.java`、`SettlementModel.java`；修改 `ModelApplication.java`、`ModelReport.java`、`PaymentModel.java`、`PaymentModelProperties.java`、`RebuildSnapshot.java`、`ScenarioLog.java`、`ScenarioRebuildTest.java`，均位于 `src/test/java/dev/ledgerlab/model/`。文档更新本文件与 `docs/design/money-model.md` 的 T15 实现备注。没有新增依赖、迁移或修改生产代码／CI。

## P1c-1 实测（2026-10-22，加入重建前）

Windows PowerShell，Java 25，Docker Desktop 的真实 PostgreSQL 18：

| 命令 | 结果 | Maven 总耗时 |
|---|---|---|
| `.\mvnw.cmd -B verify` | `Tests run: 497, Failures: 0, Errors: 0, Skipped: 0`；`BUILD SUCCESS`；含 100 条模型序列 | 02:25 min |
| `.\mvnw.cmd -B -Pmodel test -Dtest=PaymentModelProperties` | `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`；`BUILD SUCCESS`；jqwik `tries=1000, checks=1000` | 05:37 min |

1000 条报告：种子 `2026102201`，24,000 个生成动作，平均/最大长度 24/24，28,656 个检查点，违反次数 0，清理失败 0。属性含启动耗时 332.773 秒；afterClaim 崩溃 1,295 次，afterBusiness 崩溃 1,361 次，所有动作类别均有覆盖。Surefire 将整个属性计为 1 个测试，序列条数以 jqwik 和 `target/model-report.md` 为准。

## P1c-2 实测（2026-10-22，加入空库重建后）

Windows PowerShell，Java 25，Docker Desktop / PostgreSQL 18；先运行 `spotless:apply`，恢复漏投故障后依次运行：

| 命令 | 结果 | Maven 总耗时 | 重建 / 一致 |
|---|---|---|---|
| `.\mvnw.cmd -B verify` | 499 个测试，0 失败、0 错误、0 跳过；覆盖率、Spotless、模块边界检查通过；`BUILD SUCCESS` | 02:39 min | 10 / 10 |
| `.\mvnw.cmd -B -Pmodel test '-Dtest=PaymentModelProperties'` | jqwik `tries=1000, checks=1000`；`BUILD SUCCESS` | 06:52 min | 100 / 100 |

默认运行的 100 条模型序列、2,864 个检查点全部通过，模型属性耗时 40.624 秒。扩展运行共 24,000 个生成动作、28,656 个检查点，平均/最大长度 24/24，模型属性含启动耗时 407.583 秒；afterClaim 崩溃 1,295 次，afterBusiness 崩溃 1,361 次。两次运行的模型违反、清理失败和重建失败均为 0。报告同时记录动作生成种子 `2026102201` 和编号生成种子 `2026102202`。
