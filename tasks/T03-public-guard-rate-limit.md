# P7-T03 公开配置接口白名单与限流（AC-9.3、AC-9.4）

## 背景
demo-and-delivery.md 第 3、4 节；P7-T02 的演示模块。

## 范围
1. demo profile 下的路径白名单过滤器（第 4 节）：只放行静态资源、只读 GET `/api/**`、`/api/demo/**`、来自允许来源的 `/webhooks/stripe`；其余 404（不是 403，避免暴露存在性）。白名单是一张显式列表，新增控制器默认不在其中。
2. 限流（第 3 节）：内存令牌桶，动作每 IP 20/分钟、全站 300/分钟；SSE 每 IP 3 连接；429＋`Retry-After`；可信代理 CIDR 解析客户端 IP。参数可配置。
3. 文档补实现备注。

## 测试
- `ac_9_3_…`：demo profile 下逐一调用纠错、退款 API、提现 API、批次重核、对账运行、银行导入、差异处置、出站查询、任何故障钩子路径 → 404；只读 GET 仍 200；默认 profile 下这些接口行为不变（挑几条回归）。
- `ac_9_3_noModuleDependsOnDemo`：Modulith 断言。
- `ac_9_4_…`：同 IP 第 21 次动作 429 且分录数、inbox 数不变；全站上限用多个伪造来源 IP（经可信代理头）触发；不可信来源伪造 `X-Forwarded-For` 不能绕过每 IP 限额；SSE 第 4 个连接 429。时间推进用注入时钟，不 sleep。

## 可改范围
`src/main/java/dev/ledgerlab/demo/**`、`application-demo.yaml`、测试、`docs/design/demo-and-delivery.md`。

## 完成标准
按 `_common.md` 第 5 条。本地提交（`Task: P7-T03`）。
