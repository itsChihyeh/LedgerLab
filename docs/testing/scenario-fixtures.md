# P2 场景文件与独立真值（T12）

场景正本在 `simulator/src/main/resources/scenarios/`；提交的外部证据在
`src/test/resources/scenarios/<name>/`。主应用测试只读后者，不启动模拟器、不依赖模拟器
Java 类，也不读 `sim_*`。对应 AC-5.1 夹具独立性、AC-5.13、AC-5.7、AC-5.9。

## 生成与检查

在仓库根目录、Docker 已启动时运行（不需要 `.env` 或真实 Stripe）：

```powershell
.\mvnw.cmd -B -f simulator/pom.xml -Dtest=ScenarioExportTest -Dscenarios.export=true test
.\mvnw.cmd -B -Dtest=ScenarioTruthTest test
.\mvnw.cmd -B -f simulator/pom.xml verify
.\mvnw.cmd -B verify
```

Git Bash 把 `.\mvnw.cmd` 换为 `./mvnw`。第一条命令用 Testcontainers 临时库重新导出；
只有显式设置 `scenarios.export=true` 才写夹具。正常 `verify` 重新执行场景，检查文件集合及
每个文件的全部字节；漂移时失败并打印重新导出命令。新增、删除、改名证据都需审查 diff；
导出不会自动删除旧文件，过期文件会被漂移检查报出。

运行中的模拟器还提供 `POST /sim/scenarios/{name}/run`，响应是
`{"events/001.json":"原文", ..., "truth.json":"真值 JSON"}`，调用方可将每个值以 UTF-8
保存到对应相对路径。它运行同一个执行器，原文不包含签名或凭证。

执行器使用模拟器数据库角色和真实处理方、银行服务，在一个事务内完成场景，再回滚场景状态。
它要求处理方和银行状态为空，非空返回 409，不清空已有交互数据；场景期间持表锁，拒绝混入
另一轮状态。每轮重新创建种子编号器和可控 Clock；只导出业务外部编号，不导出数据库序列值。
事件交付边界改为捕获原文，因此导出不联系主应用。普通模拟器路径仍通过 WebhookSender 投递。
这也是导出模式的限制：场景运行结果供回放使用，不持久化成可继续操作的交互会话。

## 格式 v1

顶层是 `format_version: 1`、`start_clock`（UTC Instant）、`seed`（long）、有序 `actions`
及 `checkpoints`。动作字段说明如下：

| action | 字段与含义 |
|---|---|
| `charge` | `alias`、可选 `line_alias`、`request`；后者传给处理方创建 charge。`amount`/`fee` 是整数分；可选整数 epoch 秒 `available_on` 控制资金何时能进入 payout |
| `payout` | `alias`、`request`；分配当时所有可用 balance transactions |
| `reconcile` / `paid` / `failed` | `payout`（别名）、`request`；执行处理方状态转换。reconcile 是明细就绪，不是主应用对账 |
| `processor_fault` | `payout`、`request`；支持已有重复行、额外行、分页失败，以及 `omit_transaction`（隐藏指定行）、`amount`（修改之后的 payout 响应金额） |
| `bank_fault` | `request`；交给银行自己的计划接口，在接收转账时冻结计划 |
| `external_alias` | `alias`、`prefix`；分配未投递 webhook 的外部身份，例如标准样例 P9 |
| `advance` | `to`（UTC Instant），只能向前，随后推进银行到期执行 |
| `snapshot` | `business_date`；读取该多伦多业务日创建的 payout 及全部明细分页 |
| `bank_export` | `date`；从银行已执行记录导出 CSV。可选 `aliases` 按 payout 别名与从 0 开始的行 index 绑定银行流水号 |
| `checkpoint` | `name`；回放在该动作时钟调用主应用 ALL 对账 |

动作里的 `request` 和检查点中，字符串 `$P1` 等会替换为已定义的场景别名。
`payout` 字段直接使用别名，不带 `$`。重复别名、未定义别名、未知动作和倒退时钟均失败。

检查点明确写 `cutoff_at`、`evidence_as_of`、`expected_findings`。每条发现写 `kind`、
`category`、`subject_type`、`subject_id`，可选 `expected`/`actual` 关键金额和 `due_at`。
金额字段作递归子集比较；发现身份集合严格相等，不接受额外发现或缺失发现。
`payment` 类型的 subject 在导出中保留处理方支付号，回放时只为比较身份查询本地支付号映射，
不从本地状态或账本计算预期差异。空发现必须明确写 `[]`。

可选 `explained` 校验 issue 的 EXPLAINED 状态及 `issue_transition` 中的解释证据；
`replay_of` 核对旧时点重跑的完整发现、指纹和输入哈希；`expected_balances`、
`expected_entries` 对照手算样例的余额和 posting 数。

## 导出与回放

- `events/NNN.json`：按交付顺序保存签名前事件原文，无尾部换行加工；回放使用测试密钥重新签名，真实 POST 到 `/webhooks/stripe`，逐条驱动 worker 并校验入库原文字节。
- `rest/NNN.json`：每次 GET 每页原文；`manifest.json` 记录步骤时钟、请求路径、完整查询参数、状态码与响应文件。JDK HTTP 服务沿用 T04 的测试方式，严格校验请求顺序和参数；多请求、漏请求都失败。
- `bank/NNN.csv`：银行服务从自身执行记录生成的完整流水，经 `/api/bank-statements` 导入。文件内 CRLF 原样保存；局部 `.gitattributes` 禁止 Git 换行转换。
- `truth.json`：只复制场景内检查点，替换外部别名；不读取账本，不计算发现。测试故意修改真值，确认所有外部证据字节保持不变。

每个检查点重复对账，断言不新增 issue、发现相同、账本逐行不变；所有步骤完成后再读取旧报告，
证明迟到证据和新运行不覆盖历史。主应用测试用独立 Testcontainers 库，保留既有低层规则测试。

`ac-5-3` 首轮恰好 7 条发现；B2 和第二份处理方快照解释 P4。为避免附带未到账的 B2 时间差，
场景同时生成 B2 的 paid 及 11-13 银行流水，证据在第二轮 evidence_as_of 前到达。
最终再按第一轮两个时点重跑。`sample-a-1-3` 对照 A 的前 3 步；`sample-h` 包含 B1 失败、
仅 PAYOUT_FAILURE 行的重打批次及最终到账，并检查重打分录只有 2 条 posting。

权限隔离沿用 `BankTest.ac_5_13_otherRolesCannotReadWriteOrTruncateBankAndTruth`（app、readonly
直连 `sim_bank`/`sim_truth` 得到 42501），以及已有 ProcessorTest/PayoutTest 的处理方权限测试。
两个项目各有源码依赖方向检查，覆盖生产代码和测试代码。无需新增表、迁移或依赖。
