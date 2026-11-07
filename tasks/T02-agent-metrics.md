# P10-T02 调查服务耗时指标（AC-12.6）

## 背景
读 `docs/design/observability.md` 第 3 节最后一段“调查服务耗时”（照做），以及 P9 的 `docs/design/agent-workbench-and-mcp.md` 里调查服务的接口与边界（AC-11.1、AC-11.2）。代码在 `agent/service/ledgerlab_agent_service/`（`app.py` 的 HTTP 处理器、`runner.py` 跑调查）；测试在 `agent/service/tests/`（`test_static.py` 是 AC-11.2 的源码白名单检查）。

## 要做的
1. 新模块（例如 `agent/service/ledgerlab_agent_service/metrics.py`）：进程内的计数和直方图，线程安全（服务是 `ThreadingHTTPServer`），没有全局可变状态（实例挂在服务的上下文对象上）；时间来源可注入（单调时钟函数），测试里固定。输出 Prometheus 文本格式 0.0.4：每个指标有 `# HELP`、`# TYPE`；直方图桶按设计列的上界、累计计数、`le="+Inf"`、`_sum`、`_count`；标签值转义按格式规范。
2. 记录时机：每次调查结束（含 `error` 记录）按最终 `status`（`ok`／`refused`／`error`，以服务记录里的实际取值为准，没有的状态照实际枚举写，写实现备注）计一次，耗时从开始跑调查到记录写入完成；工具调用数累加进 `ledgerlab_agent_tool_calls_total`。409（并发拒绝）和 4xx 请求错误不计入调查次数。
3. `GET /agent/metrics`：`Content-Type: text/plain; version=0.0.4; charset=utf-8`；与现有 GET 一样校验 `Host`、不回 CORS 头；带查询串返回 400（与 `/agent/health` 一致的处理方式）。
4. 如果 `test_static.py` 的白名单需要为新模块调整（例如允许 `threading`、`time`），只做最小的、仍然满足 AC-11.2 文字的调整，并在最后输出里逐条说明；**不得**放开出站 HTTP、socket、shell、数据库或新的写文件方式。
5. 前端开发代理和 E2E 不需要改；`frontend/` 不动。

## 测试（新增，`test_ac_12_6_…`）
- 固定时钟下跑两次 `ok`、一次后端不可达（`error`）的调查，`GET /agent/metrics` 的文本里计数、各桶累计值、`_sum`、`_count` 精确等于预期；
- 文本格式检查：每个样本行能被一个简单的正则解析，`HELP`/`TYPE` 在样本之前，桶单调不减，`+Inf` 桶等于 `_count`；
- 并发 409 和 400 不改变计数；
- `/agent/metrics?x=1` → 400；响应无 `Access-Control-*` 头；外来 `Host` 被拒；
- AC-11.1、AC-11.2 已有测试全部继续通过。

## 可改范围
- `agent/service/**`（含测试）
- `agent/README.md`（布局表或用法里加一行）
- `docs/design/observability.md`（实现备注）
- **不许碰**：`agent/src/ledgerlab_agent/`、`agent/eval/**`、`agent/mcp/**`、`src/**`、`frontend/**`

## 完成标准
`python -m unittest discover -s agent/service/tests -t agent/service` 全绿，`agent/tests`、`agent/mcp/tests` 全绿，`sh scripts/agent/eval.sh --check` 退出 0 且工作区无改动；提交带 `Task: P10-T02`。
