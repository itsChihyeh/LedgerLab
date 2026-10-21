# ADR-0003 分录封存与数据库层不变量

状态：已采纳（P0）。来源：复核 S4；竞争者 JhanModi/double-entry-ledger。

## 背景
禁止 UPDATE/DELETE 只防住“改”和“删”。攻击者（或有 bug 的代码）仍可以给一张旧分录再插入一对平衡的 posting，借贷依然平衡，旧交易的含义却变了。

## 决定
1. 分录头带 `posting_count`、`total_amount`、`created_txid`。
2. posting 插入触发器：父分录的 `created_txid` 必须等于当前事务号，否则拒绝。
3. 延迟约束触发器在提交时检查：每个分录实际 posting 条数和 Σ借、Σ贷都等于头上的值，且 ≥ 2 条。
4. `app` 角色对分录和 posting 只有 INSERT/SELECT；对 account 只能改余额列；没有 TRUNCATE；账户属性由触发器禁止修改。
5. 测试用 `app` 角色直连数据库，覆盖：追加攻击、空分录、单边分录、不平衡分录、UPDATE、DELETE、TRUNCATE、改账户属性。

## 备选
- 只在应用层检查：绕过应用的写入（脚本、误操作、注入）就拦不住。
- 分录状态列 `OPEN/SEALED`：需要 UPDATE 权限，反而打开了改写口子。

## 后果
记账接口必须在一个事务里写完整个分录；这也是我们要的。和 JhanModi 的区别在于封存和后面的业务闭环，而不是“有触发器”本身。

## 补充（2026-10-21，开工前审查）
- 分录头和 posting **都**挂可延迟的约束触发器，调用同一个条数与合计检查；只挂在 posting 上，空分录不会被检查。
- `created_txid` 由 BEFORE INSERT 触发器写入当前事务号，调用方传什么都覆盖。
- 不可为负的账户在 `ledger.account` 上加 CHECK，不只在记账入口检查；`app` 对账户只有 `UPDATE(balance, version)`。
- 验收分开写：权限拒绝（42501）用 `app` 角色测；属性触发器用 `migrator` 角色测（`app` 根本没有那几列的权限，测不到触发器）。细节见 design/data-dictionary.md 第 0.1 节和第 1 节。
