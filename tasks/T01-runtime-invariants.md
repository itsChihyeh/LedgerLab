# P7-T01 运行时不变量 INV-1～INV-12（AC-9.9）

## 背景
demo-and-delivery.md 第 2.2 节；money-model.md 第 5 节的 INV 表；现有 `ledger.LedgerAudit`／`JdbcLedgerAudit`（INV-1～5、8）、`investigation.internal.InvariantQueryController`（P6 T04，只在 sandbox 装配）；模型测试里各 INV 的现有判定（`src/test/**` 的参考模型，只作口径参考，**不得复用测试代码**）。

## 范围
1. 公开接口 `dev.ledgerlab.ledger.InvariantCheck`（或放在一个各模块都可依赖的公开包里，按 Modulith 现有规则选位置，写进最后输出）：`String id()`、`List<Violation> check(JdbcTemplate readOnlyTx)` 之类的形状——由你定，但要能让各模块各自实现、由 investigation 统一在**一个 REPEATABLE READ 只读事务**里调用。
2. 按第 2.2 节的归属在各模块实现只读 SQL 检查：INV-6（当前可验证的部分，写实现备注）、INV-7、INV-9（2101／2103／2104／1102 四部分）、INV-10（退款与提现的资金效果唯一：用分录 source_key 和状态历史判定）、INV-11（每份已导入银行流水的期初＋入−出＝期末，以及截至最新流水期末 1103 与银行期末余额相等或差额有对应差异记录）、INV-12。已有的 INV-1～5、8 复用 `LedgerAudit`。
3. `GET /api/audit/invariants` 改为在默认、sandbox、demo 三种配置下都可用（只读、readonly 角色可执行），返回 `{checkedAt, checks:[{id, ok, violations:[…]}]}`，按 INV 编号排序，12 条都在。保持 P6 脚本用到的字段兼容（P6 的 `violations` 顶层数组继续给出全部违规的扁平列表）。
4. 设计文档 money-model 第 5 节补“运行时检查”一列的实现备注。

## 测试（真实 PostgreSQL）
- 每条 INV 一个 `ac_9_9_…` 测试：先用正常业务路径造出数据、断言 ok；再用 migrator/owner 角色直接改库（绕过应用）制造该条违规，断言接口报出且 `id` 正确。触发器挡住的（例如封存分录）用“在触发器之外能造出的最接近的违规”或说明为何只能由约束保证，写进测试注释。
- 干净库 12 条全 ok；并发写入时不误报（用栅栏：一边持续记账一边检查 20 次，全部 ok）。
- readonly 角色执行这些 SQL 不需要任何写权限（用 readonly 直连跑一遍）。
- ModularityTests 通过。

## 可改范围
`src/main/java/dev/ledgerlab/**`（各模块新增检查类与公开接口、investigation 的控制器）、对应测试、`docs/design/money-model.md`。需要新迁移（例如给 readonly 补只读视图）时按 flyway-migration skill 做。

## 完成标准
按 `_common.md` 第 5 条。本地提交（`Task: P7-T01`）。
