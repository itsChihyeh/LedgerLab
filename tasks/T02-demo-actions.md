# P7-T02 演示模块：动作白名单、SSE、计数（AC-9.1 后端部分、AC-9.6）

## 背景
demo-and-delivery.md 第 1、2 节；ADR-0017；simulator/README 的控制接口；`OutboundHooks`、`WorkerHooks`（受控崩溃）；P1b/P3/P5 的冒烟脚本里驱动这些场景的步骤（`scripts/smoke/*.py`，照着把场景翻成 Java）。依赖 P7-T01 的不变量接口。

## 范围
1. 新模块 `dev.ledgerlab.demo`，所有 bean 只在 profile `demo` 下装配；不向其他模块暴露公开类型（Modulith 测试断言没有模块依赖 demo）。配置 `ledgerlab.demo.simulator-base-url`。
2. `POST /api/demo/actions/{action}`：第 2.1 节 8 个动作，含前置自动补齐；全站一把动作锁，忙时 409。每步以 `step` 事件推送。HTTP 调 simulator 用现有 RestClient 风格。`kill-worker` 用已有崩溃钩子在 demo profile 装配一个“下一次出站命令处理在提交前崩溃一次”的触发器，然后确认租约过期后另一个 worker 接管完成（测试里用可控时钟/短租约配置）。
3. `GET /api/demo/stream`（SSE，第 2.2 节：step、timeline、invariants、counters；Last-Event-ID；连接上限）。`GET /api/demo/summary`（计数、最近一次不变量结果、主要实体数量）。`POST /api/demo/seed`（第 5 节的初始场景，幂等：已有种子就不重复建）。`POST /api/demo/visit`。
4. 迁移：`demo` schema 和 `demo.action_count`、`demo.page_view` 两张聚合表（第 2.3 节），三角色授权；app 只能 INSERT … ON CONFLICT UPDATE 计数列，readonly 只读。
5. 计数（被吸收的重复、被拒绝的超额退款等）来自账本/inbox/差异表的真实查询，不是动作计数。

## 测试
- Testcontainers 起数据库＋用 Testcontainers 或进程内方式起 simulator（照现有跨进程测试的做法；如果没有先例，用 simulator 的 jar 起子进程并在最后输出说明）。每个动作一个 `ac_9_1_…` 测试：动作完成后对应计数增加、不变量全部 ok、时间线里有预期事件类型。
- 并发点击同一动作：一个 200、一个 409。
- `ac_9_6_noPersonalData`：动作和访问后，demo schema 里只有日期聚合；响应里没有 Set-Cookie。
- 默认 profile 下 `/api/demo/**` 404；Modularity 通过。

## 可改范围
`src/main/java/dev/ledgerlab/demo/**`、各模块为崩溃触发器做的最小装配调整（行为不变）、新迁移、`src/main/resources/application-demo.yaml`、测试、`docs/design/demo-and-delivery.md`、`docs/design/data-dictionary.md`。simulator 如缺某个控制接口，先停下来问。

## 完成标准
按 `_common.md` 第 5 条。本地提交（`Task: P7-T02`）。
