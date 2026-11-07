# P10-T03 续做说明（Claude，2026-11-07）

上一个实现进程在做 T03 时因为会话掉线被中断。当前状态：
- 已提交：`a04bee8 Add a three-column inbox backlog view for the readonly role`（V24 迁移和权限测试）。**迁移已提交，永不修改。**
- 未提交的工作区改动（上一个进程留下的，未经验证）：`pom.xml`、`application.yaml`、`OutboundWorker.java`、`observability.md`，以及新文件 `InboxMetrics`、`PayoutCommandMetrics`、`ReconMetrics`、`RefundCommandMetrics`、`shared/MetricsDatabase`、`shared/OutboundCommandGauges`、`shared/ScrapeSnapshot`，测试 `ReconMetricsCategoriesTest`、`OutboundMetricsTest`、`ActuatorExposureTest`、`BusinessMetricsTest`，以及 `src/test/resources/config/`。

请先 `git status`、`git diff` 读懂这些改动，对照任务说明和“回答”把它们补完或修正（可以删掉不合适的），然后按完成标准跑测试、提交。注意 `outbound` 模块零依赖：`shared/OutboundCommandGauges` 不能被 `outbound` 用，只能被 `refunds`、`payouts` 用；如果它的位置让 `ModularityTests` 失败，就调整。`src/test/resources/config/` 若是 `application.yaml` 覆盖，确认它不改变任何已有测试的行为（只设管理端口等）。
