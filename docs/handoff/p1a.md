# P1a 交接记录

2026-10-21。阶段：P1a 账本与数据库边界（[phases.md](../design/phases.md)）。分支 `p1a-ledger`。

## 1. 已完成

| 内容 | 位置 |
|---|---|
| Maven 骨架：Spring Boot 4.1.1、Java 25、Maven Wrapper（3.10.0）、Spring Modulith 2.1.1；模块 `ledger`、`shared` | `pom.xml`、`src/main/java/dev/ledgerlab/` |
| 数据库角色：`migrator`、`app`、`readonly`，外加模拟器用的 `simulator`（拥有空的 `sim_*` schema）。初始化脚本由 Docker Compose 和 Testcontainers 共用 | `docker/postgres/initdb/01-roles.sh`、`compose.yaml`、`.env.example` |
| 第一个迁移 V1：四个 schema、资金锁、账户表（CHECK 兜底 INV-5）、分录与 posting、封存触发器（同事务追加检查、提交时条数与合计检查）、账户属性不可改、分录不可改删清空、三个只读视图、按列授权 | `src/main/resources/db/migration/V1__ledger_schema_roles_and_seal.sql` |
| 记账接口 `Ledger`：`lockFunds()` 必须先调用；`post()` 只在调用方事务里工作，账户按代码升序加锁，先查不可为负再写；重复 `source_key` 抛 `DuplicateSourceKeyException` | `ledger/Ledger.java`、`ledger/internal/JdbcLedger.java` |
| 账本审计 `LedgerAudit`：在一个 `REPEATABLE READ` 快照里重算 INV-1/2/3/4/5/8 | `ledger/internal/JdbcLedgerAudit.java` |
| 注入的时钟和 ULID 编号生成器（可带种子） | `shared/` |
| 检查工具：Spotless（palantir-java-format）、Modulith `verify()`、Error Prone、NullAway（JSpecify，`@NullMarked` 包）、JaCoCo（行 ≥ 90%、分支 ≥ 80%）、PIT（`-Pmutation`，CI 单独一个 job） | `pom.xml`、`.mvn/jvm.config`、`.github/workflows/ci.yml`、`lefthook.yml` |
| jqwik 有状态测试的起点：随机分录、重复 `source_key`、直连追加攻击，每步对照独立参考模型和审计 | `src/test/java/dev/ledgerlab/ledger/LedgerStatefulProperties.java` |
| 设计回写：数据字典第 0 节（角色怎么建、`flyway` schema）、第 1 节“P1a 实现备注”（视图名、版本号按 posting 计、自定义 SQLSTATE `LL001`～`LL004`） | `docs/design/data-dictionary.md` |

## 2. AC 与验证

`./mvnw verify` 在本机（Windows 11、Docker Desktop 29.9.1、Temurin 25.0.4）通过：编译（Error Prone、NullAway 零错误）、109 个测试全部通过、JaCoCo 门槛达标、Spotless 检查通过。PIT 结果见第 3 节第 1 条。

| AC | 测试（方法名带 AC 编号） | 结果 |
|---|---|---|
| AC-1.5 不平衡分录 | `LedgerSealTest.ac_1_5_*` | 通过：以 `app` 直连提交失败（`LL003`），无残留 |
| AC-1.6 追加攻击 | `LedgerSealTest.ac_1_6_*`（3 个） | 通过：新事务追加被拒（`LL002`）；同事务追加在提交时失败；`SET CONSTRAINTS ALL IMMEDIATE` 后在语句结束时失败 |
| AC-1.7 空分录、单边、条数不符 | `LedgerSealTest.ac_1_7_*`（5 个） | 通过：提交时 `LL003`；头上写 1 条或 posting 金额为 0 时立即 `23514` |
| AC-1.8 不可改删清空、属性不可改 | `LedgerImmutabilityTest`（27 个） | 通过：`app` 全部得到 `42501`；`migrator` 改属性得到 `LL001`、改分录得到 `LL004`；`app` 把余额改成合法但错误的值能执行，审计报 INV-2 |
| AC-1.9 不可为负 | `NonNegativeAccountTest.ac_1_9_*` | 通过：记账入口回滚整个事务（同事务里先记的合法分录也回滚）；直连改成 −1 得到 `23514` |
| AC-1.13 三个角色 | `DatabaseRolesTest`（46 个） | 通过：`readonly` 只能读视图；读底表、`io`、`sim_*`、`flyway` 和任何写入都是 `42501`；`app` 做 DDL 是 `42501`；`app` 看不到 `sim_*` |
| AC-8.1 密钥不进仓库 | lefthook 与 CI 里的 gitleaks | 通过：用合成的 `whsec_` 样例确认规则能报出；全部 Git 历史扫描无泄漏；仓库里只有空值的 `.env.example` |

另外：Spring Boot 行走骨架 `LedgerLabApplicationTests` 证明 Flyway 以 `migrator` 迁移、应用以 `app` 运行（ADR-0014 要求实测凭证）；`LedgerAuditTest` 证明审计的每类检查在被人为造出的坏状态下确实会报。

## 3. 遗留问题

1. **PIT 剩余存活变异**：门槛 80%。第一次运行 78%，暴露出三个真缺口（资金锁绑定泄漏到下一个事务时没有测试能发现、审计的 INV-1/4/5/8 从未被证明会报错、异常消息断言太宽），补测试后 65 个变异杀死 60 个（92%）。剩下 5 个：审计事务的隔离级别、只读、传播行为 3 个（单线程测试观察不到，等 P1c 有并发写入时补一个“审计期间有人记账也不报假差异”的测试）；`EntryRequest` 里借贷方向判断 2 个是等价变异（借贷互换后两边合计仍相等）。
2. **jqwik 规模**：现在每次 `verify` 跑 40 条序列、每条最多 20 步，约 40 秒。ADR-0018 要求 CI 至少 1000 条，这属于 P1c；要先把测试的数据库连接改成连接池（现在每次操作新开连接，是主要耗时），再提高条数。
3. **P1b 的表还没有直连 SQL 验收**：数据字典 0.1 节里 `core.payment`、`core.payment_transition`、`io.inbox_event`、`io.inbox_conflict`、`recon.issue` 属于 P1 表，但在 P1b 才建。P1b 的迁移要按同一格式补授权和 `42501` 测试，并把它们加进 `readonly` 不可见的检查。
4. **记账接口不校验分录模板**：`post()` 只保证借贷平衡和不可为负，不检查某个 `entry_type` 该用哪些账户（例如 `PAYMENT_CAPTURED` 必须是借 1101 / 贷 2101）。按 money-model 第 3 节加模板校验，适合在 P1b 第一个真实业务调用方出现时一起做。
5. **并发测试还没有**：AC 0.3 的栅栏和受控交错在 P3 才有对应 AC；P1a 只证明了资金锁会被持有、释放后下一事务会重新加锁。
6. **本地只读 MCP（DBHub）没连上**：需要先 `docker compose up -d` 并设置 `LEDGERLAB_READONLY_PASSWORD`。
7. **GitHub 上 CI 还没跑过**：`backend` 和新加的 `mutation` job 要等分支推上去后看结果。

## 4. 下一步：P1b 签名入站与恢复

范围和 AC 见 phases.md 的 P1b 行（AC-1.1–1.3、1.4a、1.10、1.12、2.1–2.7、4.3–4.6、8.2）。建议的切分顺序，每一步都能单独验收：

1. V2 迁移：`core.payment`、`core.payment_transition`、`io.inbox_event`、`io.inbox_conflict`、`recon.issue`，按数据字典 0.1 节写授权和直连 SQL 测试（补齐 AC-1.13）。
2. `ingestion` 模块：`POST /webhooks/stripe` 验签（Stripe 签名头、±5 分钟窗口、`livemode=true` 拒绝），落 inbox 的接收事务（AC-2.x、AC-8.2）。
3. `payments` 模块：请款准入与 `PAYMENT_CAPTURED` 记账，第一个真实的记账调用方（AC-1.1–1.3、1.4a、1.10、1.12），同时做上面第 3 节第 4 条的模板校验。
4. inbox 的抢占、业务、失败记录事务与两个 worker、受控崩溃（AC-4.3–4.6）。
5. fake Stripe 最小版（独立进程，`sim_processor` schema，`simulator` 角色）和 chaos relay 的复制、乱序、窗口内延迟。

## 5. 与 Codex 的分工（用户 2026-10-22 提出，待用户确认后写进 AGENTS.md）

- Claude Code：拆分任务，为每个任务写明范围、对应 AC、关键测试场景和完成标准；审查 Codex 的改动和测试结果；不重复实现。
- Codex：按任务实现、写测试、跑 `./mvnw verify`、修问题、提交，并附改动说明和验证结果（跑了什么、结果如何）。
- 同一时间只有一方改文件或操作 Git：Codex 在实现时，Claude 只读；Claude 审查完给出意见，Codex 再改。
- 共同的硬检查不变：lefthook 和 CI。Codex 没有 Claude 的 hooks，改完 Java 要自己跑 `./mvnw spotless:apply`（AGENTS.md 已写）。
