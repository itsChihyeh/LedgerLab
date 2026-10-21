---
name: flyway-migration
description: Use when adding or changing any database table, column, index, constraint, trigger, view or grant in LedgerLab. Covers Flyway naming, immutability of committed migrations, the three database roles and the tests every migration needs.
---

# 新增 Flyway 迁移

LedgerLab 的“只追加”“只读”等约束靠数据库兜底（ADR-0003、ADR-0014），所以每个迁移都要同时管结构和权限。

## 步骤

1. **先改设计**：字段以 `docs/design/data-dictionary.md` 为准。要改结构，先改数据字典，再写迁移。
2. **新建文件，不改旧文件**：已提交的迁移永不修改（`scripts/check-migrations.sh` 在 pre-commit 和 CI 里拦，Claude 的 hook 另拦一层）。在 `src/main/resources/db/migration/` 下新建 `V<下一个序号>__<小写下划线描述>.sql`，序号取现有最大值加一。
3. **写结构**：
   - 金额 `BIGINT`（分），时刻 `timestamptz`，平台生成的实体主键用带前缀的 ULID 文本（`docs/design/contracts.md`），自然键、外部编号和联合主键按数据字典各表定义（例如账户用 `smallint` 代码、inbox 用处理方事件号），每张表有 `recorded_at`。
   - 能用约束表达的规则就用约束（`CHECK`、`UNIQUE`、部分唯一索引、外键），不要只靠应用代码。
   - 账本相关的触发器（分录封存、借贷平衡、禁止改账户属性）照 ADR-0003。
4. **同一个迁移里写授权**（对照数据字典第 0 节）：
   - `app`：只授予该表需要的最小权限；分录和 posting 只有 INSERT/SELECT；绝不授予 TRUNCATE。
   - `readonly`：只授予视图的 SELECT，不直接授予底表；`io` 和 `sim_*` schema 对它不可见。
   - 模拟器的表放在 `sim_processor` / `sim_bank`，主应用角色不可见。
5. **写测试**（Testcontainers 真 PostgreSQL）：
   - 迁移能在空库上从头跑到最新。
   - 每条新约束至少一个“违反时被数据库拒绝”的测试。
   - 每个新授权至少一个“越权被拒绝”的测试：用 `app` 或 `readonly` 直连，执行不该允许的操作，断言权限错误。
6. **跑** `./mvnw verify`，确认全绿后再说完成。

## 不要

- 在迁移里插业务数据（种子数据走测试夹具或模拟器）。
- 用 `CREATE ... IF NOT EXISTS` 掩盖顺序问题。
- 为了让测试通过而放宽授权。
