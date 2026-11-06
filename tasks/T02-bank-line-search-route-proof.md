# P8-T02 后端：按 reference 查银行流水；服务端证明 Agent 路由只读

## 背景
读 `docs/design/investigation-agent.md` 第 3、9 节，ADR-0014，AC-10.2、AC-10.3。看 `investigation/internal/WorkbenchQueries.java` 的 `bankDetail`（`recon.v_bank_line` 视图、`QuerySnapshot` 的 REPEATABLE READ 快照与 `asOf`）、`WorkbenchQueryController.java`、`ReadonlyConfiguration.java`，以及现有调查接口测试（`grep -rln "bank-lines\|investigationDataSource" src/test/java`）和 Modulith 测试。T01 已在 `agent/src/ledgerlab_agent/routes.json` 登记了 Agent 能用的全部路由。

## 范围
1. **AC-10.3** `GET /api/bank-lines?reference=<ref>`（investigation 模块，readonly 数据源，单个 REPEATABLE READ 快照）：返回 `{reference, lines:[BankLine…], asOf}`，`lines` 是该 reference 的每个 `bank_txn_id` 的当前版本（取最高 version），按 booking_date、bank_txn_id 排序；每条带 `versions` 计数或直接复用现有 `BankLine` 形状（与 `/api/bank-lines/{id}` 的 `bankLine` 字段一致，便于 Agent 引用）。`reference` 必填，1–128 个可见字符，否则 400；没有匹配返回 200 空数组（不是 404）。最多 100 条，超过时 `truncated:true`。不新增迁移；现有视图不够时停下来问。与已有的 `/api/bank-lines/{bankTxnId}` 共存（注意路由不冲突）。
2. **AC-10.2** 新测试类 `AgentRouteAllowlistTest`（Spring Boot 测试＋Testcontainers）：读 `agent/src/ledgerlab_agent/routes.json`（相对仓库根目录；找不到文件就让测试失败，不跳过），对每条路由：
   - `method` 必须是 `GET`；`path_template` 以 `/api/` 开头、不以 `/api/demo` 开头。
   - 用 `RequestMappingHandlerMapping` 找到处理该模板的 handler（把 `{param}` 替换成示例值去匹配），断言 handler 所在类属于 `dev.ledgerlab.investigation` 或是 payouts 的只读查询控制器（列出允许的类名，明确写死）。
   - 断言同一路径模式上不存在任何非 GET 的映射。
   - 断言 handler 所在 bean 的依赖链（`ConfigurableListableBeanFactory.getDependenciesForBean` 递归）里包含 `investigationDataSource`，且不包含主数据源 bean（按现有配置确认主数据源的 bean 名；拿不准就停下来问）。
   - 对每条路由实际发一次请求（空库下 200 或 404 都可以，不能 5xx），确认在测试环境能走通。
   - 断言 `routes.json` 里没有 `resolve`、`corrections`、`cancel`、`seed`、`actions`、`query`（POST 查询）之类写语义的路径片段。
3. 在 `docs/design/investigation-agent.md` 第 3 节加实现备注：新接口的返回形状；`AgentRouteAllowlistTest` 检查什么。在 `docs/design/contracts.md` 调查接口相关处补一行新接口说明。

## 关键测试场景
- `ac_10_3_searchByReferenceReturnsEveryCurrentLine`：同一 reference 下 SPLIT 的两条、DUPLICATE 的两条都返回；有多版本的只返回当前版本；`asOf` 存在。数据用现有测试的银行导入方式造（看现有 `BankImport`/`BankRule` 测试怎么造流水，不直接写 recon 表以外的东西；如果现有测试有造数辅助，复用）。
- `ac_10_3_unknownReferenceIsEmptyAndBadReferenceIs400`。
- `ac_10_3_searchRunsOnReadonly`：沿用现有调查接口证明 readonly 的写法（例如在同一快照里尝试写被拒，或检查 `current_user`）。
- `ac_10_2_*`：上面第 2 条每一项一条测试或一个参数化测试。

## 可改范围
`src/main/java/dev/ledgerlab/investigation/**`、`src/test/java/**`（新增测试类、必要的测试夹具）、`docs/design/investigation-agent.md`、`docs/design/contracts.md`（实现备注）。如 `routes.json` 里的路由模板和实际 Java 路由对不上，可以修正 `agent/src/ledgerlab_agent/routes.json` 并同步修 `agent/tests/` 里受影响的测试（然后跑 Python 测试）。**不改**迁移、其他模块的主代码、`simulator/`、`frontend/`。

## 完成标准
`./mvnw -B test-compile spotless:check` 通过；`./mvnw -B test -Dtest='AgentRouteAllowlistTest,<本任务新增的其他测试类>,ModularityTests' -Dsurefire.failIfNoSpecifiedTests=false` 全绿；改了 `agent/` 就再跑 `python -m unittest discover -s agent/tests -t agent`。提交带 `Task: P8-T02`。
