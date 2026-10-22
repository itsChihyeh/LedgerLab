# LedgerLab fake Stripe（P1b）

独立 Spring Boot 4.1.1 / Java 25 Maven 项目，不是根项目子模块、不依赖主应用 Java 代码。仅实现 `charge.succeeded` snapshot 事件；`relay` 包预留给后续任务。

## 构建与启动

在仓库根目录运行，需要 Java 25、Docker（测试使用真实 PostgreSQL 18）：

```sh
./mvnw -B -f simulator/pom.xml verify
./mvnw -f simulator/pom.xml spotless:apply
```

PowerShell 用 `.\mvnw.cmd` 替换 `./mvnw`。从根目录调用 Wrapper 时，`-f` 仍加载根 `.mvn/jvm.config`；编译实际执行 Error Prone 和 NullAway，verify 检查 Spotless 及 JaCoCo 行覆盖率 ≥80%。测试复制根数据库初始化脚本，隔离测试只读取根迁移文件建出真实 `ledger.v_account`，不引用主应用类。

先按根 README 配置 PostgreSQL 并 `docker compose up -d`。初始化脚本创建 `simulator` 角色与四个空 schema。模拟器运行和 Flyway 均以 simulator 登录；历史表位于 sim_processor，不创建 schema。

环境变量（进程必须实际继承；Spring 不会自动读取根 `.env`）：

| 变量 | 必需 / 默认值 |
|---|---|
| `LEDGERLAB_SIMULATOR_PASSWORD` | 必需，与 PostgreSQL 初始化一致 |
| `SIM_STRIPE_WEBHOOK_SECRET` | 必需，与主应用 `STRIPE_WEBHOOK_SECRET` 相同 |
| `SIM_DATABASE_URL` | `jdbc:postgresql://localhost:5432/ledgerlab` |
| `SIM_STRIPE_WEBHOOK_URL` | `http://localhost:8080/webhooks/stripe` |
| `SIM_PORT` | `8081` |
| `SIM_SEED` | `1`，编号随机序列种子 |
| `SIM_CLOCK_START` | 可选 ISO-8601 UTC 时刻；默认进程启动时刻 |

```sh
./mvnw -f simulator/pom.xml spring-boot:run
# 或先 verify 后运行独立 jar：
java -jar simulator/target/ledgerlab-simulator-0.0.1-SNAPSHOT.jar
```

编号同种子同序列，重放完整场景应使用空模拟器数据库；保留数据重新启动并创建新场景时使用不同种子，避免重用旧编号。模拟时钟冻结在起始值，场景驱动代码注入 `SimulationClock` 并调用 `advance(Duration)` 快进；P1b 不添加时钟控制 HTTP 接口。用三天前的 `SIM_CLOCK_START` 可直接生成旧事件；签名始终使用独立现实 UTC 时钟。快速连续重投同事件时，签名秒数至少比上次大一秒；大量紧密重投可能超出主应用五分钟窗口，应按真实重试节奏调用。

## 控制接口

全部为模拟器接口，不是 Stripe REST API。返回 HTTP 200；结构错误返回 400；不存在的事件返回 404。

| 接口 | 输入 / 返回 |
|---|---|
| `POST /sim/charges` | 必填整数 `amount`；返回 `{charge, event_id}` 并立即投递 |
| `POST /sim/events/{id}/redeliver` | 无请求体；原文不变，用新签名时间投递；返回 `{event_id}` |
| `POST /sim/events/{id}/reissue` | 无请求体；同一 Charge 快照、新事件号和事件 created；返回 `{charge, event_id}` |
| `GET /sim/events/{id}` | `{payload, deliveries}`；payload 为精确原文字符串，投递按 id 排序 |

```sh
curl -H 'Content-Type: application/json' -d '{"amount":10000}' http://localhost:8081/sim/charges
curl -X POST http://localhost:8081/sim/events/evt_REPLACE/redeliver
curl -X POST http://localhost:8081/sim/events/evt_REPLACE/reissue
curl http://localhost:8081/sim/events/evt_REPLACE
```

默认 `currency=cad`、`captured=true`、`status=succeeded`、`amount_captured=amount`、`livemode=false`；均可覆盖来构造准入拒绝场景。金额仅接受 long 范围 JSON 整数，不进行主应用的业务范围检查。`livemode=true` 只是合成 JSON 字段，不调用真实 Stripe。

charge 与 event 原子提交后发送 HTTP；无自动重试。每次完成的投递记录目标 URL、签名秒数、现实记录时刻，以及 HTTP 状态码或网络错误类型。500 也记录状态码。连接超时 5 秒、请求超时 10 秒；进程在发送与日志落库之间崩溃可能留下无投递结果的事件，调用 redeliver 手动补投（P1 无可靠投递队列）。

协议固定 `2026-09-30.endive`；对象子集参考 [Event](https://docs.stripe.com/api/events/object) 和 [Charge](https://docs.stripe.com/api/charges/object)，签名按 [Stripe webhook 文档](https://docs.stripe.com/webhooks/signature) 对 UTF-8 原文字节计算 HMAC-SHA256。P6 再用脱敏真实夹具做版本契约校验。

fake Stripe 只实现所需子集，行为以真实 Stripe 为准。真实测试模式的结算单与 webhook 同源、证据独立性较弱；对账准确率只在有独立真值的模拟器模式评测，P1b 暂无结算及对账实现。
