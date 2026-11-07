# 演示站 AWS 部署方案与报价（P7-T07，AC-9.11）

状态：**仅方案，未执行**。本文件与 `deploy/` 下的文件不创建任何云资源、不需要 AWS 凭证。设计依据：[demo-and-delivery.md](../design/demo-and-delivery.md) 第 9 节。

## 需要用户做的事

以下每一项都需要用户本人决定或用本人账号操作，确认之前什么都不会发生。

1. **批准一个方案和月费上限**（推荐方案见下；价格见下一条）。
2. **核实剩余价格**：EC2 实例、EBS gp3、公网 IPv4、CloudWatch Logs／自定义指标、SES 发信的单价已从 AWS 官方价格文件核实（P10-T05，查询日期 2026-11-07 UTC；EC2 另有 2026-11-05 的一次）；出站流量、Lightsail、Fargate、RDS、ALB、Route 53 仍是“估算”，免费额度的适用条件也待核（原因见“价格说明”）。批准前请用 [AWS Pricing Calculator](https://calculator.aws/) 或下面的官方链接核价。
3. **提供 AWS 账号**：自己开账号，或建一个只用于本项目的账号／IAM 用户；开启账单预算告警（建议月预算 = 批准的上限）。MFA 打开。
4. **（可选）域名**：自己的域名，把 A 记录指向实例的弹性 IP。没有域名时可以用第三方的 IP 解析域名服务（例如 sslip.io）拿到一个主机名让 Caddy 申请证书，但这是外部服务，是否使用由用户决定。
5. **确认仓库地址和要部署的标签／提交**（填进 `cloud-init.yaml` 的占位符）；私有仓库还需要只读部署密钥，由用户在 GitHub 上建，**不要**写进任何文件。
6. **确认 Docker Compose 插件和 Caddy 镜像的固定版本**（`cloud-init.yaml` 里留了占位符，需要在当天查官方发布页并核对校验和）。
7. **按 `deploy/README.md` 的步骤逐步执行**，哪些步骤是用户本人账号操作在那里标出。
8. **（P10）决定可观测性档位**：最低档（只做日志轮转）还是推荐档（`t4g.medium`＋Prometheus），见“P10 可观测性”一节的月费。
9. **（P10）决定日志去向**：只留在实例上（每日重置会丢），还是送 CloudWatch Logs（要给实例角色加日志写入权限，打破“除 SSM 外不加权限”，由用户决定）。
10. **（P10）决定告警通知渠道**：只在 Prometheus 页面看（零成本）、SES 邮件，还是自己的 SMTP。**发信是对外发送，Claude 不做、也不替用户开通。** （P11 补充）仓库里的 Alertmanager（`ops/alertmanager/alertmanager.yml`，演示栈 `observability` profile 里的 `alertmanager` 服务）目前只把告警送到本机接收程序 `scripts/ops/alert-sink.py`，**没有任何真实渠道**；云上那台机器没有这个接收程序，所以不选渠道时这些告警在云上只是留在 Alertmanager 页面里。选渠道（邮件、聊天软件、推送）是用户的决定：选定后只需加一个接收方和一条路由，地址和凭证放在实例上仓库之外的文件里（`url_file` 等），不进仓库。
11. **（P10）启用 Prometheus 时设 `LEDGERLAB_DEMO_OBSERVABILITY=1`**：`scripts/demo`（T05b，已批准）据此对每次 compose 调用（`up`、`down`、`ps`、`logs`）带 `--profile observability`；不设时行为不变。`up.sh` 开启时先检查外部卷 `ledgerlab-prometheus-data` 和 `ops/prometheus/alerts.yml`，缺了就报错退出（不自动建卷）。变量要同时写进 `up.sh` 和每日重置 cron 两处。
12. **（P10）本机装 Session Manager 插件**（自己的电脑，用于端口转发看 Prometheus）；确认 `prom/prometheus` 镜像的固定版本（草稿里是 `v3.5.0`，当天查官方发布页并考虑固定摘要）。

## 价格说明（重要）

- **已核实（P10-T05 追加，2026-11-07 UTC，区域 ca-central-1）**：EBS gp3、公网 IPv4、CloudWatch Logs 摄入／存储、CloudWatch 自定义指标、SES 发信的单价，同样来自 AWS 公开价格文件，由扩展后的 `deploy/price-check.py` 查询；完整输出见“P10 可观测性”一节的“核价输出”。**免费额度（如 CloudWatch 的 Global 0～5 GB 档）只是价格文件里单价为 0 的档，适用条件待用户核实，下面的月费都不依赖它。**
- **已核实**：EC2 `t4g.small`、`t4g.medium`、`t3.small` 的 Linux 按需价格，来自 AWS 公开价格文件 <https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/ca-central-1/index.json>，由 `deploy/price-check.py` 在本机查询，**查询日期 2026-11-05（UTC）**，区域 ca-central-1：
  - `t4g.small`：0.0184 USD/小时 → 按 730 小时/月约 **13.43 USD/月**
  - `t4g.medium`：0.0368 USD/小时 → 约 **26.86 USD/月**
  - `t3.small`（x86 退路）：0.0232 USD/小时 → 约 **16.94 USD/月**，比 `t4g.small` 多 0.0048 USD/小时，约 +3.50 USD/月。
- **估算（标“估算”，未核实）**：脚本查不到的项目，数字只用来比较三个方案的量级，不是报价。官方来源：
  - 出站流量：<https://aws.amazon.com/ec2/pricing/on-demand/>
  - Lightsail：<https://aws.amazon.com/lightsail/pricing/>
  - Fargate：<https://aws.amazon.com/fargate/pricing/>；RDS：<https://aws.amazon.com/rds/postgresql/pricing/>；负载均衡：<https://aws.amazon.com/elasticloadbalancing/pricing/>
  - Route 53（可选）：<https://aws.amazon.com/route53/pricing/>；域名注册价按后缀，见 <https://aws.amazon.com/route53/pricing/> 的域名注册表
- AC-9.11 状态：**部分满足：价格待用户核实**（EC2、EBS、公网 IPv4 已核实，出站流量、Lightsail、Fargate、RDS、ALB、Route 53 待核）。

## 方案 A（推荐）：单台 EC2 + Caddy + 本机 PostgreSQL 容器

| 组件 | 说明 | 月费（USD；除标“已核实”外均为估算） |
|---|---|---|
| EC2 `t4g.small`（2 vCPU ARM，2 GiB），按需，ca-central-1 | 跑 `compose.demo.yaml`（PostgreSQL、模拟器、app）和 Caddy | **13.43**（0.0184×730，2026-11-05 已核实） |
| EBS gp3 30 GiB | 系统＋镜像＋数据库卷（每天重置，数据量很小） | **2.64**（0.088×30，2026-11-07 已核实） |
| 公网 IPv4（弹性 IP） | HTTPS 入口 | **3.65**（0.005×730，2026-11-07 已核实） |
| 出站流量 | 演示页很小；新账号和常规免费额度内通常为 0，超出按 GB 计 | 约 0～2 |
| CloudWatch | 只用基础指标（免费）；不开详细监控、不收日志 | 0 |
| Route 53 托管区（可选，用自己的域名时） | 域名注册另计，按域名后缀 | 约 0.5＋域名年费 |
| **合计** | | **约 19.7～21.7，含 Route 53 约 22.2**（EC2、EBS、公网 IPv4 三行共 19.72 已核实；流量和 Route 53 是估算） |

- **可用性与运维负担**：单点，实例或区挂了就停；演示数据每天重置，丢了无损失。运维项：系统补丁、Docker 镜像更新、磁盘空间、cron 是否在跑。每日 03:15 UTC 重置期间（约几分钟）页面不可用，演示页和文章里应写明。
- **内存**：2 GiB 跑 PostgreSQL、两个 JVM 和首次构建很紧；cloud-init 加了 2 GiB swap。若实测仍不稳（构建被 OOM 杀、运行时频繁换页），升到 `t4g.medium`（已核实约 26.86/月，比 `t4g.small` 多约 13.43/月；价格 2026-11-05 查得，2026-11-07 复查未变），或把构建放到本地、把镜像推到仓库——后者会引入镜像仓库，需另行批准。
- **构建位置**：`scripts/demo/up.sh` 需要主机上有 JDK 25 和 Docker（用 `mvnw` 构建两个 jar，前端在 Docker 里构建），cloud-init 装 Corretto 25（包名需核实）。
- **架构**：ARM。`postgres:18`、`node:24`、`eclipse-temurin:25-*` 官方镜像都有 arm64 版本；首次部署后要实测一次 `up.sh` 能通过（这是未验证项）。
- **安全要点**
  - 安全组只开入站 80 和 443（全部来源）；**不开 22**，不开 28080／28081／25432（这几个端口本来也只绑 127.0.0.1）。
  - 登录用 SSM Session Manager（实例角色挂 `AmazonSSMManagedInstanceCore`），不放 SSH 密钥。
  - 实例角色除 SSM 外不加任何权限；实例上没有 AWS 凭证需要保管。
  - 镜像和仓库里没有密钥：数据库口令由 `scripts/demo` 首次运行时随机生成到实例上的 `.demo/secrets.env`（权限 600）；演示站只有 fake Stripe 的公开测试假值，没有任何真实 Stripe 凭证。
  - Caddy 对 `/api/demo/seed`、`/webhooks/*`、`/sim/*`、`/relay/*` 直接返回 404，是应用内来源检查之后的第二道防线。Caddy 用覆盖的方式写 `X-Forwarded-For`，客户端伪造的值到不了应用的限流器。
  - EBS 加密打开（默认密钥即可）；开启实例元数据 IMDSv2 only。
- **依赖 T04c：部署时设 `LEDGERLAB_DEMO_TRUSTED_PROXIES`**。应用限流按客户端地址分桶，Caddy 经发布端口访问应用时应用看到的是 Compose 网络网关（`10.89.77.1/32`）；T04c 完成前该变量传不进 app 容器，所有访客会共用同一个“每 IP 每分钟 20 次动作”的桶（全站上限 300 不受影响）。T04c 合入后，部署时把该变量设为 `10.89.77.1/32`。

## P10 可观测性（AC-12.9，仅方案，未执行）

状态：**只有方案、报价和草稿文件，没有执行任何云操作**；没装 AWS CLI、没登录、没用任何凭证，唯一的联网是 `deploy/price-check.py` 对 AWS 公开价格文件的只读 GET。设计依据：[observability.md](../design/observability.md) 第 2、5 节，[ADR-0021](../adr/0021-observability.md)。本节在方案 A 之上增加日志、指标和告警；方案 B、C 不在 P10 范围。

### 核价输出

`python deploy/price-check.py` 在本机跑了一次（约 7 秒，区域 ca-central-1，**查询日期 2026-11-07 UTC**；输出里 `published` 是各价格文件自己的发布日期）：

```text
Queried 2026-11-07 UTC, region ca-central-1
-- AmazonEC2 (price list published 2026-11-07T00:28:52Z)
EC2 Linux on-demand                t3.small: 0.0232000000 USD per Hrs
                                     ($0.0232 per On Demand Linux t3.small Instance Hour)
EC2 Linux on-demand                t4g.medium: 0.0368000000 USD per Hrs
                                     ($0.0368 per On Demand Linux t4g.medium Instance Hour)
EC2 Linux on-demand                t4g.small: 0.0184000000 USD per Hrs
                                     ($0.0184 per On Demand Linux t4g.small Instance Hour)
EBS gp3 storage                    CAN1-EBS:VolumeUsage.gp3: 0.0880000000 USD per GB-Mo
                                     ($0.088 per GB-month of General Purpose (gp3) provisioned storage - Canada (Central))
-- AmazonVPC (price list published 2026-11-05T18:19:45Z)
Public IPv4 address                CAN1-PublicIPv4:IdleAddress: 0.0050000000 USD per Hrs
                                     ($0.005 per Idle public IPv4 address per hour)
Public IPv4 address                CAN1-PublicIPv4:InUseAddress: 0.0050000000 USD per Hrs
                                     ($0.005 per In-use public IPv4 address per hour)
-- AmazonCloudWatch (price list published 2026-11-04T21:35:32Z)
CloudWatch                         CAN1-CW:MetricMonitorUsage: 0.3000000000 USD per Metrics [tier 0..10000]
                                     ($0.30 per metric-month for the first 10,000 metrics - Canada (Central))
CloudWatch                         CAN1-CW:MetricMonitorUsage: 0.1000000000 USD per Metrics [tier 10000..250000]
                                     ($0.10 per metric-month for the next 240,000 metrics - Canada (Central))
CloudWatch                         CAN1-CW:MetricMonitorUsage: 0.0500000000 USD per Metrics [tier 250000..1000000]
                                     ($0.05 per metric-month for the next 750,000 metrics - Canada (Central))
CloudWatch                         CAN1-CW:MetricMonitorUsage: 0.0200000000 USD per Metrics [tier 1000000..Inf]
                                     ($0.02 per metric-month for over 1,000,000 metrics - Canada (Central))
CloudWatch                         CAN1-DataProcessing-Bytes: 0.5500000000 USD per GB
                                     ($0.55 per GB custom log data ingested in Standard log class - Canada (Central))
CloudWatch                         CAN1-TimedStorage-ByteHrs: 0.0330000000 USD per GB-Mo
                                     ($0.033 per GB-mo of log storage - Canada (Central))
CloudWatch                         Global-CW:MetricMonitorUsage: 0.0000000000 USD per Metrics [tier 0..10]
                                     (Global)
CloudWatch                         Global-DataProcessing-Bytes: 0.0000000000 USD per GB [tier 0..5]
                                     (Global)
CloudWatch                         Global-TimedStorage-ByteHrs: 0.0000000000 USD per GB-Mo [tier 0..5]
                                     (Global)
-- AmazonSES (price list published 2026-11-04T17:41:09Z)
SES                                CAN1-AttachmentsSize-Bytes: 0.1200000000 USD per GB
                                     (USD0.12 per GB for AttachmentsSize-Bytes:Send in Canada (Central))
SES                                CAN1-Essentials-Outbound-Email: 0.0001600000 USD per Count [tier 0..10000000]
                                     ($0.00016 per Count from 0 to 10,000,000 for Essentials-Outbound-Email in Canada (Central))
SES                                CAN1-Essentials-Outbound-Email: 0.0001400000 USD per Count [tier 10000000..100000000]
                                     ($0.00014 per Count from 10,000,000 to 100,000,000 for Essentials-Outbound-Email in Canada (Central))
SES                                CAN1-Essentials-Outbound-Email: 0.0001100000 USD per Count [tier 100000000..Inf]
                                     ($0.00011 per Count from 100,000,000 for Essentials-Outbound-Email in Canada (Central))
SES                                CAN1-Message: 0.0001500000 USD per Count
                                     ($0.00015 per Count for Message processed in Canada (Central))
SES                                CAN1-Message: 0.0001000000 USD per Count
                                     (USD0.0001 per Count for Message:Receive in Canada (Central))
Lightsail, Fargate, RDS and ALB: read them from the pages listed in aws-plan.md.
```

读法：`Global-*` 行是 0～5 GB（或 0～10 个指标）档单价为 0，疑为免费额度，适用条件待核，本节不计入。SES 在该区域同时有 `Message`（0.00015 USD/封）和 `Essentials-Outbound-Email`（0.00016 USD/封）两种计量，不确定账号会落在哪种，下面取较高的 0.00016。

### 日志

- **去向与轮转**：容器 stdout 的 JSON 日志走 Docker 默认的 `json-file` 驱动，`/etc/docker/daemon.json` 设 `max-size: 20m`、`max-file: 5`（每个容器最多约 100 MB，四个容器合计不超过约 400 MB，不会撑满 30 GiB 的卷）。免费。`LEDGERLAB_LOG_FORMAT=logstash`：`compose.demo.yaml` 的 app 环境已固定，`cloud-init.yaml` 的 `/etc/ledgerlab/demo.env` 里也写出（后者只是让选择可见，不起作用）。草稿见 `deploy/cloud-init.yaml`。
- **重要限制（按脚本逻辑推断，未在 AWS 上验证）**：`json-file` 日志跟着容器走，而每日 03:15 的重置是 `down -v` 后重新 `up`，容器被重建，**前一天的 stdout 日志随之消失**。所以只靠 `json-file`，日志最长保留到下一次重置（不到一天）。想留得更久，要么送 CloudWatch Logs（见下），要么在重置前把日志转存到 `/var/lib/ledgerlab/archive/`（要改 `scripts/demo`，不在本任务范围，需用户决定）。
- **日志量估算**（每天约 **10 MB**，一个月约 0.3 GB）。依据（都是假设，上线后用 `docker logs <容器> | wc -c` 实测）：
  - 每行 JSON 约 500 B（`logstash` 格式带 MDC 键）。
  - 访问日志：约 100 个访客/天 × 约 40 个请求/访客（页面加静态资源）≈ 4,000 行。
  - 业务事件：约 100 次演示动作/天 × 约 15 行（`webhook.received`、`inbox.processed`、`outbound.attempt`、`recon.*` 等）≈ 1,500 行。
  - 每日重置时的播种和对账：约 6,000 行（拍脑袋的量级）。
  - 合计约 11,500 行 × 500 B ≈ 5.8 MB，**取整并乘 2 作安全系数 = 10 MB/天**。
- **可选的 CloudWatch Logs 月费**（单价来自上面的核价输出）：摄入 0.3 GB × 0.55 = **0.165 USD**；保留 14 天的存储约 0.14 GB × 0.033 = **0.005 USD**；合计约 **0.17 USD/月**。悲观情形（日志量×10，3 GB/月）约 1.65＋0.05 ≈ **1.7 USD/月**。查询（Logs Insights）另计，按扫描量收费，演示站用得很少，未计。
- **送 CloudWatch 的代价**：需要给实例角色加 `logs:CreateLogGroup`、`logs:CreateLogStream`、`logs:PutLogEvents`，或在实例上装 CloudWatch 代理。这打破了“实例角色除 SSM 外不加任何权限”的安全要点，**由用户决定**。使用 Docker 的 `awslogs` 驱动还要改 `compose.demo.yaml` 的 `logging:`，本任务没做。

### 指标与告警：同机 Prometheus

- **形态**：`compose.demo.yaml` 的可选服务 `prometheus`（`profiles: [observability]`，默认不起），镜像 `prom/prometheus:v3.5.0`（固定版本，上线前核对并考虑固定摘要），`--storage.tsdb.retention.time=7d`、`--storage.tsdb.retention.size=512MB`，只发布 `127.0.0.1:29090`；抓取配置 `deploy/prometheus.demo.yml`（抓 Compose 网络里的 `app:8079`），告警规则只读挂载 `ops/prometheus/`（由 P10-T04 创建）。app 的管理端口 8079 **不发布到主机**；为了让 Prometheus 容器够得着，`compose.demo.yaml` 给 app 设了 `MANAGEMENT_SERVER_ADDRESS=0.0.0.0`（设计里默认是 `127.0.0.1`，在容器里别的容器连不上）。历史数据放外部卷 `ledgerlab-prometheus-data`，每日 `down -v` 不会清掉。Caddy 对 `/actuator` 返回 404 作为第二道防线。
- **查看**：用 SSM 端口转发，在用户自己的电脑上（需要本机装 Session Manager 插件）：
  `aws ssm start-session --target <实例ID> --document-name AWS-StartPortForwardingSession --parameters '{"portNumber":["29090"],"localPortNumber":["29090"]}'`，然后浏览器开 `http://127.0.0.1:29090/alerts`。不开安全组端口，不公开。
- **启用前必须先解决的事（已实测）**：每日重置脚本（`scripts/demo/demo.py`）调用 `docker compose down -v` 时不带 profile。用一个一次性的 Compose 项目实测：带 `--profile observability` 启动后，不带 profile 的 `down` **不会停掉带 profile 的容器，并且因为网络仍被占用而报 “Resource is still in use”**，也就是说照现在的脚本直接启用 Prometheus 会让每日重置失败。所以 `scripts/demo` 在 `LEDGERLAB_DEMO_OBSERVABILITY=1` 时对每次 compose 调用统一加 `--profile observability`（T05b 已实现，`selftest.py` 覆盖开与不开两种命令行；尚未用真实演示栈跑过，因为 `ops/prometheus/` 还没建）。不设该变量时 Prometheus 保持关闭，也不影响演示站。（另外，`python_env()` 会清掉所有 `COMPOSE_*` 环境变量，所以不能靠 `COMPOSE_PROFILES`。）同时实测确认：外部卷不存在时，不带 profile 的 `up` 不受影响。
- **内存是否还够 2 GiB**（**估算，未实测**；典型常驻内存，上线后用 `free -m`、`docker stats --no-stream` 实测）：

  | 组成 | 估算（GiB） |
  |---|---|
  | 系统＋Docker＋SSM 代理＋cron | 0.35～0.45 |
  | PostgreSQL 容器（数据很小） | 0.15～0.30 |
  | app（Spring Boot JVM；容器无内存限制时默认最大堆为主机内存的 1/4，约 0.5） | 0.45～0.70 |
  | 模拟器（JVM） | 0.35～0.50 |
  | Caddy | 0.03～0.05 |
  | **小计（不含 Prometheus）** | **约 1.4～2.0** |
  | Prometheus（约 1,000～1,500 个序列，15 秒一次，保留 7 天约 100 MB 磁盘；`mem_limit: 256m` 封顶） | 0.10～0.25 |
  | Alertmanager（P11；空闲时很小，`mem_limit: 64m` 封顶；估算，未实测） | 0.02～0.06 |
  | **合计** | **约 1.5～2.3，而 `t4g.small` 只有 2.0（可用约 1.9）** |

  结论：不带 Prometheus 时 2 GiB 已经紧（P7 已为此加了 2 GiB swap）；带 Prometheus 大概率不够，**推荐换 `t4g.medium`（4 GiB）**。差价：0.0368 − 0.0184 = 0.0184 USD/小时，约 **+13.43 USD/月**（P7 数字，2026-11-05 核实，2026-11-07 复查未变）。`mem_limit: 256m` 保证 Prometheus 超了只会自己被杀，不拖垮 app。
- **序列数的数法**（上线后核实 Prometheus 的内存估算）：`curl -s http://127.0.0.1:8079/actuator/prometheus | grep -vc '^#'`（在 app 容器内或经管理端口）得到当前序列数；Prometheus 页面里 `prometheus_tsdb_head_series` 也是这个数。

### 告警通知

- **零成本选项（默认）**：不装 Alertmanager，只在 Prometheus 的 `/alerts` 页面看（经 SSM 端口转发）。缺点是没人看就没人知道，但对一个每日重置、没有真实用户的演示站可以接受。
- **Alertmanager→邮件（可选）**：同机再起一个 Alertmanager 容器（约 20～40 MiB，**估算**），经 SES 或用户自己的 SMTP 发邮件。**发信是对外发送，必须由用户决定，Claude 不替用户开通 SES、不验证域名、不创建 SMTP 凭证。** 价格（已核实）：SES 发信 0.00016 USD/封（上面的核价输出），一个月即使 50 封告警也只有 **约 0.01 USD**；附件 0.12 USD/GB，用不到。SES 新账号默认在沙盒，只能发给已验证的地址，需要用户验证收件地址或申请出沙盒。注意：Alertmanager 经 SES SMTP 发信需要一个 SMTP 凭证（由 IAM 用户生成），这是**要放在实例上的密钥**，和“实例上没有 AWS 凭证需要保管”相冲突；用户自己的 SMTP 也一样要放口令。这个取舍由用户定，且口令不得写进仓库。（P11 更新：仓库现在有 Alertmanager 配置和演示栈服务，但接收方只有本机程序，没有 SES 或 SMTP 的配置；`mem_limit: 64m`，无数据卷，静默随每日重置丢失，见上面的内存估算表。）

### 备选：CloudWatch 代理抓 Prometheus 端点（不自建 Prometheus）

- 做法：CloudWatch 代理抓应用的 Prometheus 端点，转成 CloudWatch 自定义指标，在 CloudWatch 里建告警。省掉 Prometheus 容器的内存（代理自己约 50～100 MiB，**估算**），但：需要在实例上装代理、给实例角色加 `CloudWatchAgentServerPolicy` 一类权限（同样打破“只有 SSM”）；管理端口当前不发布到主机，代理在主机上够不着，需另行发布 `127.0.0.1:28079` 或把代理放进 Compose 网络（本任务没改）。规则要重写成 CloudWatch 告警，`promtool` 的两层测试不再适用。**这条路径没有验证，以下只是价格。**
- 单价（核价输出）：自定义指标 **0.30 USD/指标/月**（前 10,000 个）；标准分辨率告警 **0.10 USD/告警指标/月**（价格文件的 `CW:AlarmMonitorUsage`，2026-11-07 查得）。代理以日志事件的形式上报 Prometheus 指标，因此还会有少量日志摄入费（0.55 USD/GB），量级低于 0.1 GB/月，**待核**。
- **数指标的方法**：CloudWatch 把“指标名＋全部维度取值的组合”算一个指标。用 `curl -s http://127.0.0.1:8079/actuator/prometheus | grep '^ledgerlab_' | grep -v '_bucket' | grep -vc '^#'` 数出本项目自己的序列数（每个标签组合一行；JVM 和 HTTP 自带指标按需要才抓，**不要全抓**）。
- 按设计第 3 节的标签基数，上限估算：`inbox.backlog` 2＋`oldest.due.age` 1＋`outbound.attempts` 最多 kind×outcome×retry ≈ 16＋`outbound.unfinished` 2×3 = 6＋`outbound.exhausted` 2＋`recon.issues.open` 最多约 10 个类别＋`recon.issues.oldest.open.age` 1 ≈ **38 个**（kind 和类别的个数是估计，**以实际数出来的为准**）→ 38 × 0.30 ≈ **11.4 USD/月**；再加一条告警 0.10。**最小可用**（只上报 `ledgerlab_inbox_oldest_due_age_seconds` 一个指标＋一条告警）= 0.30 + 0.10 = **0.40 USD/月**。
- 这条备选的总月费：方案 A（19.7～21.7）＋ 0.4～11.5，仍在 `t4g.small` 上，**但 2 GiB 是否够仍要实测**。

### 月费合计（方案 A＋可观测性）

| | 最低档 | 推荐档 |
|---|---|---|
| 内容 | `t4g.small`；`json-file` 轮转；不起 Prometheus；不送云；不发信 | `t4g.medium`；`json-file` 轮转；同机 Prometheus 7 天；可选 CloudWatch Logs；可选 SES 邮件 |
| EC2（已核实） | 13.43 | 26.86 |
| EBS gp3 30 GiB（已核实） | 2.64 | 2.64 |
| 公网 IPv4（已核实） | 3.65 | 3.65 |
| 出站流量（估算） | 0～2 | 0～2 |
| 可观测性增量 | 0 | CloudWatch Logs 约 0.17（0.17～1.7，可选）＋ SES 约 0.01（可选） |
| **合计（不含 Route 53）** | **约 19.7～21.7** | **约 33.2～35.2（含两个可选项约 33.3～36.9）** |
| Route 53（可选，估算） | ＋0.5 | ＋0.5 |

- “最低档”不需要 `scripts/demo` 的改动，也不增加任何费用；缺点是看不到指标和告警，只有 JSON 日志（且每日重置会丢）。
- “推荐档”比方案 A 多约 **13.4～15.1 USD/月**，其中 13.43 来自换实例。若用户愿意先在 `t4g.small` 上实测内存，可以先按最低档上线，数据支持时再升级实例并启用 Prometheus（实例类型可以停机后变更）。
- 这些数字里“已核实”的只有 EC2、EBS、公网 IPv4、CloudWatch 和 SES 单价；日志量、内存、序列数是估算，已在各处标明。

## 方案 B（备选，更省事）：Lightsail 实例

| 组件 | 说明 | 月费（USD；除标“已核实”外均为估算） |
|---|---|---|
| Lightsail Linux 实例（2 GiB 档位） | 固定月费，含系统盘、静态 IP 和一定流量额度 | 约 10～12 |
| 域名（可选） | Lightsail DNS 免费托管区；域名注册另计 | 0＋域名年费 |
| **合计** | | **约 10～12** |

- 组件与方案 A 相同（Docker＋Caddy＋同一套 `deploy/` 文件），只是不用管 VPC、EBS、弹性 IP。Lightsail 默认是 x86 还是 ARM 档位需在创建时确认（`cloud-init.yaml` 里的 Compose 下载链接是 aarch64，x86 要改成 x86_64）。
- 可用性与运维负担：与 A 同级（单点、自己打补丁）；账单更可预测。限制：网络与 IAM 能力比 EC2 简单，防火墙只开 80/443，SSH 默认开着要关掉，改用浏览器 SSH 或在需要时临时开。
- 安全要点与 A 相同；另外 Lightsail 实例没有 SSM 的完整支持，管理登录靠控制台浏览器终端。

## 方案 C（备选，更“生产化”）：ECS Fargate + RDS

| 组件 | 说明 | 月费（USD；除标“已核实”外均为估算） |
|---|---|---|
| Fargate 任务 ×2（app、simulator），各 0.5 vCPU / 1 GiB 起 | 常驻 | 约 30～45 |
| RDS PostgreSQL `db.t4g.micro` 单可用区＋存储 | 托管数据库 | 约 15～20 |
| Application Load Balancer | HTTPS 终止（ACM 证书免费，需要域名） | 约 18～25 |
| NAT 或公网 IP、日志、ECR 存储 | 视网络布局 | 约 5～35 |
| **合计** | | **约 70～120** |

- 可用性：托管数据库有自动备份、滚动更新；但演示数据每天重置，这些能力用不上，不匹配这个项目的需求。
- 需要额外工作：把 Compose 改成任务定义、把镜像推 ECR、每日重置从“down -v”改成“清库重建”（RDS 里 app 角色没有删除权，要换用迁移账号重建 schema），`scripts/demo` 不能直接复用。工作量大，不建议。
- 安全要点：安全组分层（ALB 对外 443，任务只接受 ALB，数据库只接受任务）；Secrets Manager 放数据库口令（额外按密钥计费）；ECS Exec 代替 SSH。

## 三方案对比

| | A EC2 | B Lightsail | C Fargate＋RDS |
|---|---|---|---|
| 月费（A 的 EC2、EBS、IPv4 已核实） | 约 19.7～22（含 P10 推荐档约 33～37） | 约 10～12 | 约 70～120 |
| 复用现有脚本 | 完全 | 完全 | 需要改造 |
| 可用性 | 单点 | 单点 | 较好但用不上 |
| 运维 | 中 | 中偏低 | 高 |
| 推荐 | **是** | 预算优先时 | 否 |

## 费用控制

- 部署前在账号里建月度预算告警（建议阈值 80% 和 100%）。
- 不用时停掉实例：EC2 停止后不收实例费，但 EBS 和弹性 IP 仍收费；彻底不用就删除实例、卷和弹性 IP（`deploy/README.md` 末尾有清单）。
- 加了 CloudWatch Logs 或自定义指标后，在预算告警之外看一眼 CloudWatch 的用量，日志量比估算大很多时先调低保留天数。
- 实例不要放进有 NAT 网关的 VPC（NAT 按小时计费，对这个规模不划算）。
