# P7-T07 AWS 部署方案与报价（AC-9.11，只写不做）

## 背景
demo-and-delivery.md 第 9 节；P7-T04 的 `compose.demo.yaml`。**不得执行任何 AWS 操作或创建任何云资源**，也不需要 AWS 凭证。

## 范围
1. `docs/deploy/aws-plan.md`：推荐方案（单台 EC2 t4g.small＋Caddy HTTPS＋本机 PostgreSQL 容器＋每日重置定时任务）和两个备选（Lightsail；ECS Fargate＋RDS）。每个方案列：组件、月费估算（按 ca-central-1 按需价格，查 AWS 官方价格页，写明查询日期和链接；含 EBS、出站流量、可选域名）、可用性与运维负担、安全要点（只开 80/443、SSH 用 SSM、密钥不进镜像）。
2. `deploy/`：`Caddyfile`、`cloud-init.yaml` 草稿（装 Docker、拉代码、起 `compose.demo.yaml`、配每日 `reset.sh`）、`deploy/README.md`（用户确认后要做的每一步，标出哪些需要用户本人账号操作）。
3. “需要用户做的事”清单单独一节放在 aws-plan.md 开头。

## 可改范围
`docs/deploy/**`、`deploy/**`、`docs/design/demo-and-delivery.md`。

## 完成标准
文档完成；`git grep` 无任何凭证形状的字符串。本地提交（`Task: P7-T07`）。
