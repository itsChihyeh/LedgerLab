# 演示站 AWS 部署方案与报价（P7-T07，AC-9.11）

状态：**仅方案，未执行**。本文件与 `deploy/` 下的文件不创建任何云资源、不需要 AWS 凭证。设计依据：[demo-and-delivery.md](../design/demo-and-delivery.md) 第 9 节。

## 需要用户做的事

以下每一项都需要用户本人决定或用本人账号操作，确认之前什么都不会发生。

1. **批准一个方案和月费上限**（推荐方案见下；价格见下一条）。
2. **核实剩余价格**：EC2 实例价格已于 2026-11-05 从 AWS 官方价格文件核实；EBS、公网 IPv4、出站流量、Lightsail、Fargate、RDS、ALB、Route 53 仍是“估算”（原因见“价格说明”）。批准前请用 [AWS Pricing Calculator](https://calculator.aws/) 或下面的官方链接核价。
3. **提供 AWS 账号**：自己开账号，或建一个只用于本项目的账号／IAM 用户；开启账单预算告警（建议月预算 = 批准的上限）。MFA 打开。
4. **（可选）域名**：自己的域名，把 A 记录指向实例的弹性 IP。没有域名时可以用第三方的 IP 解析域名服务（例如 sslip.io）拿到一个主机名让 Caddy 申请证书，但这是外部服务，是否使用由用户决定。
5. **确认仓库地址和要部署的标签／提交**（填进 `cloud-init.yaml` 的占位符）；私有仓库还需要只读部署密钥，由用户在 GitHub 上建，**不要**写进任何文件。
6. **确认 Docker Compose 插件和 Caddy 镜像的固定版本**（`cloud-init.yaml` 里留了占位符，需要在当天查官方发布页并核对校验和）。
7. **按 `deploy/README.md` 的步骤逐步执行**，哪些步骤是用户本人账号操作在那里标出。

## 价格说明（重要）

- **已核实**：EC2 `t4g.small`、`t4g.medium` 的 Linux 按需价格，来自 AWS 公开价格文件 <https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/ca-central-1/index.json>，由 `deploy/price-check.py` 在本机查询，**查询日期 2026-11-05（UTC）**，区域 ca-central-1：
  - `t4g.small`：0.0184 USD/小时 → 按 730 小时/月约 **13.43 USD/月**
  - `t4g.medium`：0.0368 USD/小时 → 约 **26.86 USD/月**
  - `t3.small`（x86 退路）：脚本已加入，**尚未查询，待核实**。
- **估算（标“估算”，未核实）**：脚本查不到的项目，数字只用来比较三个方案的量级，不是报价。官方来源：
  - EBS：<https://aws.amazon.com/ebs/pricing/>
  - 公网 IPv4 地址（含运行中的实例）：<https://aws.amazon.com/vpc/pricing/>
  - Lightsail：<https://aws.amazon.com/lightsail/pricing/>
  - Fargate：<https://aws.amazon.com/fargate/pricing/>；RDS：<https://aws.amazon.com/rds/postgresql/pricing/>；负载均衡：<https://aws.amazon.com/elasticloadbalancing/pricing/>
  - Route 53（可选）：<https://aws.amazon.com/route53/pricing/>；域名注册价按后缀，见 <https://aws.amazon.com/route53/pricing/> 的域名注册表
- AC-9.11 状态：**部分满足：价格待用户核实**（EC2 已核实，其余待核）。

## 方案 A（推荐）：单台 EC2 + Caddy + 本机 PostgreSQL 容器

| 组件 | 说明 | 月费（USD；除标“已核实”外均为估算） |
|---|---|---|
| EC2 `t4g.small`（2 vCPU ARM，2 GiB），按需，ca-central-1 | 跑 `compose.demo.yaml`（PostgreSQL、模拟器、app）和 Caddy | **13.43**（0.0184×730，2026-11-05 已核实） |
| EBS gp3 30 GiB | 系统＋镜像＋数据库卷（每天重置，数据量很小） | 约 3 |
| 公网 IPv4（弹性 IP） | HTTPS 入口 | 约 3.7 |
| 出站流量 | 演示页很小；新账号和常规免费额度内通常为 0，超出按 GB 计 | 约 0～2 |
| CloudWatch | 只用基础指标（免费）；不开详细监控、不收日志 | 0 |
| Route 53 托管区（可选，用自己的域名时） | 域名注册另计，按域名后缀 | 约 0.5＋域名年费 |
| **合计** | | **约 20～25，含可选项约 26**（其中仅 EC2 一行已核实） |

- **可用性与运维负担**：单点，实例或区挂了就停；演示数据每天重置，丢了无损失。运维项：系统补丁、Docker 镜像更新、磁盘空间、cron 是否在跑。每日 03:15 UTC 重置期间（约几分钟）页面不可用，演示页和文章里应写明。
- **内存**：2 GiB 跑 PostgreSQL、两个 JVM 和首次构建很紧；cloud-init 加了 2 GiB swap。若实测仍不稳（构建被 OOM 杀、运行时频繁换页），升到 `t4g.medium`（已核实约 26.86/月，比 `t4g.small` 多约 13.43/月），或把构建放到本地、把镜像推到仓库——后者会引入镜像仓库，需另行批准。
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
| 月费（仅 EC2 已核实） | 约 20～26 | 约 10～12 | 约 70～120 |
| 复用现有脚本 | 完全 | 完全 | 需要改造 |
| 可用性 | 单点 | 单点 | 较好但用不上 |
| 运维 | 中 | 中偏低 | 高 |
| 推荐 | **是** | 预算优先时 | 否 |

## 费用控制

- 部署前在账号里建月度预算告警（建议阈值 80% 和 100%）。
- 不用时停掉实例：EC2 停止后不收实例费，但 EBS 和弹性 IP 仍收费；彻底不用就删除实例、卷和弹性 IP（`deploy/README.md` 末尾有清单）。
- 实例不要放进有 NAT 网关的 VPC（NAT 按小时计费，对这个规模不划算）。
