# P10-T05 真实部署方案与报价更新（AC-12.9，只写文档和草稿，不执行）

## 背景
读 `docs/design/observability.md` 第 2 节（管理端口）和第 5 节（照做），P7 的 `docs/deploy/aws-plan.md`、`deploy/README.md`、`deploy/Caddyfile`、`deploy/cloud-init.yaml`、`deploy/price-check.py`，以及 `docs/design/demo-and-delivery.md` 第 9 节。**绝对不执行任何 AWS 操作**：不装 AWS CLI、不登录、不建资源、不用任何凭证。唯一允许的联网是 `price-check.py` 那种对 AWS **公开**价格文件的只读 HTTPS GET（不需要凭证、不花钱）。

## 要做的
1. `deploy/price-check.py`：扩展成也能查设计第 5 节需要的公开价格（至少 CloudWatch Logs 摄入／存储单价、SES 发信单价，区域 `ca-central-1`；EBS gp3、公网 IPv4 如果能在公开价格文件里找到也加上）。仍然只用标准库、只读、流式解析大文件；查不到的项打印 `NOT FOUND`，不编数。跑一次，把输出（含查询日期）贴进 `aws-plan.md`。如果网络受限跑不通，写“待核价”并说明原因。
2. `docs/deploy/aws-plan.md`：新增“P10 可观测性”一节（放在方案 A 之后）：
   - 日志：Docker `json-file` 日志驱动＋轮转（给出 `max-size`/`max-file` 值），`LEDGERLAB_LOG_FORMAT=logstash`；按演示站日志量（自己估一个每天多少 MB，写出估算依据）算可选的 CloudWatch Logs 月费。
   - 指标与告警：同机 Prometheus 容器（固定版本，保留 7 天，只听 127.0.0.1），用 SSM 端口转发查看；内存估算和 2 GiB 是否够；不够时换 `t4g.medium` 的差价（P7 已有数字可复用，注明出处和日期）。
   - 告警通知：Alertmanager→邮件（SES 或用户自己的 SMTP）。**发信是对外发送，由用户决定**；给出 SES 价格和“只在 Prometheus 页面看”的零成本选项。
   - 备选：CloudWatch 代理抓 Prometheus 端点，列价格（自定义指标单价×本项目指标数，写出数指标的方法）。
   - 更新“需要用户做的事”和月费合计（方案 A＋可观测性的最低／推荐两档）。
3. `deploy/Caddyfile`：`@internal_only` 加 `/actuator /actuator/*`。
4. `deploy/cloud-init.yaml`：设 `LEDGERLAB_LOG_FORMAT=logstash`；Docker daemon 日志轮转（`/etc/docker/daemon.json`）；可选起 Prometheus 的步骤用注释写出、默认不执行。
5. `compose.demo.yaml`：加一个可选的 `prometheus` 服务，放在 Compose `profiles: [observability]` 下（默认不起），固定版本镜像，只绑 `127.0.0.1`，只读挂 `ops/prometheus/`（这个目录由 T04 创建；如果你做的时候还不存在，就照设计第 4 节的文件名引用，并在最后输出里说明）。app 的管理端口不对外发布，Prometheus 在 Compose 网络里抓 `app:8079`。
6. `deploy/README.md`：步骤里加上可观测性相关的验证（`curl -i https://<域名>/actuator/health` 应为 404；SSM 端口转发看 Prometheus）。

## 可改范围
- `docs/deploy/aws-plan.md`、`deploy/**`、`compose.demo.yaml`（只加可选服务）
- `docs/design/observability.md`（实现备注）
- **不许碰**：`src/**`、`agent/**`、`frontend/**`、`ops/**`

## 测试与验证
- `python deploy/price-check.py` 跑一次（记录耗时和输出）；
- `docker compose -f compose.yaml -f compose.demo.yaml --profile observability config -q`（或仓库里演示栈实际用的文件组合，看 `scripts/demo/up.sh`）通过；
- 如果仓库有检查 Caddyfile 的现成测试（`git grep -n Caddyfile src/test scripts`），跑它；没有就用 `docker run --rm -v ...:/etc/caddy caddy:<P7 用的版本> caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile`，需要的环境变量给假值。

## 完成标准
上述验证通过；文档里每个价格都有来源和日期，或明确写“待核价”；提交带 `Task: P10-T05`。
