# P7-T06 k6 性能基线（AC-9.10）

## 背景
demo-and-delivery.md 第 8 节；ADR-0004（2102 串行瓶颈）；P7-T04 的演示栈（性能测试用独立项目名 `ledgerlab-perf`、端口 38080/38081/35432，避免和演示栈、沙箱栈冲突）。

## 范围
1. `perf/` 下三个 k6 脚本（W1 webhook 入站、W2 单商户退款受理、W3 只读查询）和一个驱动 `perf/run.sh`：用 `docker run grafana/k6:<固定版本>`（查官方最新稳定版，写明版本和日期）连本机栈；W1 的签名用和 compose 冒烟一样的公开测试 whsec 在 k6 里按 Stripe 方案计算；W2 先用 fake Stripe 准备 200 笔已结算支付。每个场景跑 3 次取中位数。
2. 跑完调 `/api/audit/invariants`，任何违规则整轮作废并在报告里写明。
3. 报告 `docs/perf/baseline-<UTC 日期>.md`＋同名 `.json`：机器配置（CPU、内存、OS、Docker 资源限制）、版本（提交号、JDK、PostgreSQL）、每个场景的吞吐与 p50/p95/p99、错误率、inbox 排空时间、瓶颈分析（实测 2102 串行下的退款 TPS，和多商户按商户分片的说明）。数字只能来自这次实测。
4. README 加一行链接到报告（不写数字以外的宣传语）。

## 执行者和实现进程都要实际跑
`sh perf/run.sh`（可能要 20～40 分钟）。不得影响 `ledgerlab-sandbox`。

## 可改范围
`perf/**`、`docs/perf/**`、README、`compose.*.yaml`（只加 perf 用的叠加文件）、`docs/design/demo-and-delivery.md`。不改业务代码；发现性能缺陷只记录，停下来问是否修。

## 完成标准
报告生成并提交。本地提交（`Task: P7-T06`）。
