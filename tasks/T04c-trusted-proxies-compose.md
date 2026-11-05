# P7-T04c 部署在反向代理后时，按访客真实 IP 限流（AC-9.10 补）

## 背景
demo-and-delivery.md 第 3、4 节、第 13 节“客户端地址”。`application-demo.yaml` 已经读 `LEDGERLAB_DEMO_TRUSTED_PROXIES`，但 `compose.demo.yaml` 没把它传进 app 容器，`scripts/demo/demo.py` 也没设。部署方案（`deploy/Caddyfile`）里 Caddy 用 host 网络，转发到发布端口，所以 app 看到的连接地址是 Compose 网关 `10.89.77.1`。不补的话，所有访客会共用同一个每 IP 每分钟 20 次的桶。

## 范围
1. `compose.demo.yaml`：在 app 的环境变量里加 `LEDGERLAB_DEMO_TRUSTED_PROXIES: ${LEDGERLAB_DEMO_TRUSTED_PROXIES:-}`。默认为空，本地演示的行为不变。
2. `scripts/demo/demo.py`（以及 `up.sh` 的参数说明）：加一个可选开关，例如 `--behind-proxy` 或环境变量 `LEDGERLAB_DEMO_BEHIND_PROXY=1`。打开后把 trusted-proxies 设为 `10.89.77.1/32`；不打开就保持为空。`deploy/cloud-init.yaml` 和 `deploy/README.md` 改成打开这个开关。
3. 测试（Testcontainers 或现有的 DemoPublicSurfaceTest 风格，不 mock 数据库）。方法名带 `ac_9_10_`：
   - 连接地址是可信代理时，`X-Forwarded-For` 不同的两个访客各自有独立的桶：A 用满 20 次后，B 仍然可以操作。
   - 连接地址不是可信代理时，伪造的 `X-Forwarded-For` 无效，两个访客共用一个桶。
   - **管理入口不能借道代理**：请求来自可信代理、并带 `X-Forwarded-For` 时，`/api/demo/seed` 等管理接口仍返回 404。这一条防止网关地址既算可信代理、又算管理来源时被利用。如果现有逻辑做不到，停下来提问，不要自己改判定规则。
4. 在 `docs/design/demo-and-delivery.md` 补一行实现备注。

## 可改范围
`compose.demo.yaml`、`scripts/demo/**`、`deploy/**`、`src/test/java/dev/ledgerlab/demo/**`、`docs/design/demo-and-delivery.md`。**不改** `src/main/**`。如果测试证明必须改 main 代码，停下来提问。

## 完成标准
相关测试通过；`python scripts/demo/selftest.py`（若存在）通过；默认启动（不开开关）时行为不变。本地提交，提交信息带 `Task: P7-T04c`。

## 执行者验证
全量 `verify`；`sh scripts/demo/up.sh` 以默认方式起一次栈，然后 `sh scripts/demo/status.sh`，最后 `sh scripts/demo/down.sh`。
