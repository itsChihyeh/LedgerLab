# P7-T04b CI 不再静默跳过演示测试；seed 与 webhook 只认本机直连（审查修复）

## 背景
P7-T02 报告第 4 条（`DemoActionsTest` 在没有 simulator jar 时 `assumeTrue` 整类跳过，CI 主应用 job 不先构建 simulator）；P7-T03 报告第 1 条（`POST /api/demo/seed` 对公开开放）；`DemoGateFilter`；demo-and-delivery.md 第 4、5、9 节。云端审查已决定，照做不用再问。

## 范围
1. **CI**：`.github/workflows/` 的主应用 job 在跑测试前先 `./mvnw -B -f simulator/pom.xml -DskipTests package`。`DemoStack` 的跳过逻辑改为：环境变量 `CI=true` 时 jar 不存在就**失败**（不是跳过），本地没有 jar 时仍跳过但打印明确提示。
2. **管理入口只认本机直连**：新增配置 `ledgerlab.demo.admin-allowed-sources`（默认只有 `127.0.0.1/32`、`::1/128`）。`POST /api/demo/seed`（以及 T04 若加了其他供脚本调用的写入口，如存档相关）只有同时满足“连接地址在该列表里”且“请求**没有** `X-Forwarded-For`、`Forwarded`、`X-Real-IP` 任一头”时放行，否则 404。原因：部署时反向代理在同一台机器上，公网请求经代理到达时连接地址也是回环，但代理会加转发头。
3. `/webhooks/stripe` 的来源检查同样加上“不得带转发头”这一条（relay 在 compose 网络里直连，不经代理）。确认 compose 里 relay 到 app 的来源地址在 `webhook-allowed-sources` 里（必要时把演示栈的 compose 网络网段写进 `application-demo.yaml` 或 `compose.demo.yaml` 的环境变量）。
4. 设计文档第 4、5 节补实现备注；第 9 节（部署）加一句：反向代理配置里显式拒绝 `/api/demo/seed` 和 `/webhooks/stripe` 的外部访问，作为第二道防线（T07 写 Caddyfile 时照做）。

## 测试
- seed：回环直连无转发头 → 200；回环但带 `X-Forwarded-For` → 404；非允许来源 → 404。webhook 同样三种。
- `DemoStack`：`CI=true` 且 jar 缺失 → 抛出失败（用可注入的环境读取做单测，不真的删 jar）。
- 既有 Demo* 测试全绿。

## 可改范围
`.github/workflows/**`、`src/main/java/dev/ledgerlab/demo/**`、`application-demo.yaml`、`compose.demo.yaml`（若 T04 已建）、Demo 相关测试、`docs/design/demo-and-delivery.md`。

## 完成标准
按 `_common.md` 第 5 条。本地提交（`Task: P7-T04b`）。
