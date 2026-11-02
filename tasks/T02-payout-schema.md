# T02 提现表结构与授权（主应用迁移 V21）

## 背景
ADR-0006 补充第 1 节；数据字典 `core.payout`、`recon.bank_line`、`io.outbound_command`；ADR-0014（角色）；skill `flyway-migration`；V15（退款与幂等）、V20（纠错）作范例。

## 范围
1. V21：`core.payout`（数据字典字段＋ADR 补充字段；状态 CHECK `RESERVED/SUBMITTED/UNKNOWN/ACCEPTED/PAID/FAILED/RETURNED`；`PAID/RETURNED` 要求 `bank_txn_id`，`RETURNED` 要求 `return_bank_txn_id`，`FAILED` 要求 `failure_reason`；`amount` 1～99999999；`merchant_payout_ref` UNIQUE；`initial_result jsonb` 非空不可改）、`core.payout_status_history`（只追加，触发器拒绝改删清空）。
2. `recon.bank_line`：加 `matched_payout_id` FK，`match_status` 加 `PAYOUT_PAID`/`PAYOUT_RETURNED`，CHECK 按 ADR；`app` 可 UPDATE 列加 `matched_payout_id`。
3. 差异 subject_type 已含 `payout`；确认差异类别里 `PAYOUT_STATE_CONFLICT`、`PAYOUT_UNRESOLVED` 已在 CHECK 中（没有就加）。
4. 纠错：`ledger.correction.correction_type` CHECK 加 `CONFIRM_FAILED_PAYOUT_PAID`（实现留 T07）。
5. 出站命令 kind 已有 `SUBMIT_PAYOUT`/`QUERY_PAYOUT` 就不改，没有就加。
6. 授权：`app` 对 payout INSERT/SELECT 及 UPDATE(status 与各时刻、bank_* 列、failure_reason、return_* 列、updated_at)；history 只 INSERT/SELECT；`readonly` 只读脱敏视图 `core.v_payout`、`core.v_payout_status_history`。
7. 数据字典补实现备注（第 0.1 节列权限同步）。

## AC / 测试场景
- 迁移/权限测试：app 不能 UPDATE `amount`、`merchant_payout_ref`、`initial_result`；不能删 payout 和历史；readonly 只能读视图；状态与证据列的 CHECK 违反时报错；bank_line 新状态的 CHECK。
- 现有迁移测试全部通过。

## 可改范围
新迁移 `src/main/resources/db/migration/V21__*.sql`、迁移/权限测试、`docs/design/data-dictionary.md`。

## 完成标准
按 `_common.md` 第 5 条（加上已有迁移/权限测试类）；本地提交（`Task: T02`）。
