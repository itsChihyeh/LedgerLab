# deploy/：演示站上云的草稿文件

**这些文件没有被执行过，也不会自己创建任何云资源。** 方案、报价和风险见 [docs/deploy/aws-plan.md](../docs/deploy/aws-plan.md)；先读那份文件开头的“需要用户做的事”。

| 文件 | 用途 |
|---|---|
| `Caddyfile` | HTTPS 反向代理；对 `/api/demo/seed`、`/webhooks/*`、`/sim/*`、`/relay/*` 返回 404 |
| `cloud-init.yaml` | 首次启动脚本草稿：装 Docker／JDK、拉代码、起 `compose.demo.yaml`（经 `scripts/demo/up.sh`）、起 Caddy、配每日重置 |
| `price-check.py` | 只读查询 AWS 公开价格文件，不需要凭证 |

## 步骤

标 **[用户]** 的必须由用户本人账号或决定完成，Claude 不做。

1. **[用户]** 阅读并批准 `aws-plan.md` 的方案和月费上限；运行 `python3 deploy/price-check.py` 或看官方页，把核实后的价格和日期填回 `aws-plan.md`。
2. **[用户]** 开 AWS 账号（或专用 IAM 用户），开 MFA，建月度预算告警。
3. **[用户]** （可选）准备域名。
4. 解决 `aws-plan.md`“已知缺口”：让 app 容器收到 `LEDGERLAB_DEMO_TRUSTED_PROXIES=10.89.77.1/32`（需要改 `compose.demo.yaml` 和 `scripts/demo/demo.py`，由后续任务做）。
5. 填 `cloud-init.yaml` 里的占位符：`<DEMO_DOMAIN>`、`<REPO_URL>`、`<GIT_TAG_OR_COMMIT>`、`<COMPOSE_VERSION>`、`<CADDY_VERSION>`；核对 Compose 插件的校验和，并核实 JDK 25 的包名。**[用户]** 确认这些值；私有仓库的只读部署密钥由用户建，不写进文件。
6. **[用户]** 在 AWS 控制台创建（用户本人账号）：
   - 一个只有入站 80、443 的安全组（不开 22）；
   - 带 `AmazonSSMManagedInstanceCore` 的实例角色；
   - 一台 `t4g.small`，Amazon Linux 2023 arm64，30 GiB 加密 gp3，IMDSv2 only，user data 贴入填好的 `cloud-init.yaml`；
   - 一个弹性 IP 并绑定到实例；域名的 A 记录指向它。
7. **[用户]** 用 SSM Session Manager 登录，查看 `/var/log/cloud-init-output.log`；首次构建在 2 GiB 内存上可能要十几分钟。
8. 验证（用户在自己的浏览器或终端）：
   - `https://<域名>/` 打开演示页，12 条不变量为绿；
   - `curl -i https://<域名>/api/demo/seed -X POST` 和 `curl -i https://<域名>/webhooks/stripe -X POST` 都得到 404；
   - 点一个动作，时间线在 2 秒内出现条目（说明 SSE 没被代理缓冲）；
   - 次日查看 `/var/lib/ledgerlab/archive/` 有前一天的摘要，页面上“上次重置时间”前进。
9. **[用户]** 观察第一周的账单。

## 下线与清理（避免继续计费）

**[用户]** 终止实例，删除它的 EBS 卷、释放弹性 IP、删除安全组和实例角色；如建过 Route 53 托管区也删除。归档的每日摘要在 `/var/lib/ledgerlab/archive/`，下线前自行取走需要的。
