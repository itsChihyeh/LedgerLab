# ADR-0011 受限纠错：冲销加重记

状态：已采纳（P0）。来源：复核 I2，细化 AC-6。

## 背景
AC-6 只允许冲销，但把“账记错了”和“给顾客退款”混在一起。手续费记多了，不该靠退款修。

## 决定
- 没有通用的改账接口。
- 唯一的纠错路径：一条 `ledger.correction` ＋ 对原分录的完整 `REVERSAL`（每条 posting 方向取反）＋ 零或一个 `CORRECTION` 分录（正确的 postings），三者同一事务。
- 必须关联原分录、原因（枚举＋说明）和证据（差异编号、结算单行、流水编号）；一个分录最多被冲销一次。
- 业务事件（退款、提现失败、提现退回）永远不用 `REVERSAL`。
- 纠错不改业务对象的历史状态；如果纠错影响业务对象（例如支付金额），业务对象记一条状态历史，证据指向这次纠错。

## 后果
纠错留下完整的审计链；页面上可以从差异一路点到纠错分录和证据。

## 补充（2026-10-21，开工前审查）
- 冲销加重记只保证分录配平，业务对象（支付金额、预期手续费、退款额度、批次状态）不会自动跟着改。样例 E 冲销 4500、重记 5400 以后，支付表金额仍是 4500，批次照样核对不过。
- **P4 开工前要补**：列出有限的几种纠错类型（未结算支付金额修正、无后续义务的误记撤销、待查收款应用（含拆分到账，一次处理多条银行流水）、银行冲突证据恢复），每种写清分录、业务字段、状态历史、退款额度和证据如何在一个事务里一起变，以及哪些情况明确不支持。需要扩大 `app` 的列权限时，新增迁移并同步数据字典第 0.1 节。

## 补充（2026-10-25，P4 开工前置：纠错类型）

Claude 按用户授权定稿，供用户过目。本节是 P4 的设计正本；与上文冲突处以本节为准。

### 1. 总规则

- 一次纠错 = 一条 `ledger.correction` ＋ 它的目标分录清单 `ledger.correction_target` ＋ 0～n 个 `REVERSAL` ＋ 0～1 个 `CORRECTION`，**至少一个分录**，与业务字段、状态历史、差异处置在同一事务提交或回滚。上文“唯一路径是一个 `REVERSAL` 加零或一个 `CORRECTION`”放宽为“由纠错类型决定分录形状”：撤销与改金额仍是一冲一记；待查收款应用不是记错账，而是把一笔正确记下但归属未知的钱归位，只用一个 `CORRECTION`（不冲销原入账，不碰 1103）。
- 目标分录角色：`REVERSED`（被本次 `REVERSAL` 完整取反）或 `APPLIED`（`UNAPPLIED_CASH_RECEIVED` 被归位）。每个分录**一生最多作为一次目标**（`correction_target.entry_id` UNIQUE），`journal_entry.reverses_entry_id` UNIQUE 保持不变。
- `REVERSAL` 的 postings 由账本自己从原分录生成（逐条方向取反、金额相同），调用方不能提交冲销行；数据库在提交时用延迟约束触发器核对每个 `REVERSAL` 与原分录逐条镜像、每条 `REVERSED` 目标恰有一个同 `correction_id` 的 `REVERSAL`、每条 correction 至少一个分录。
- 只有下面 4 种类型；没有通用改账接口（AC-6.1），也没有“任意冲销某分录”的入口。
- 必填：`type`、`reasonCode`（枚举）、`reasonNote`（非空，≤2000 字）、`evidence`（非空数组，元素 `{type, id}`，`type ∈ issue / statement_line / bank_txn / inbox_event / processor_fetch`，每个都必须存在）、`requestedBy`（非空）。缺任何一项或证据不存在 → 400，无写入（AC-6.7）。
- `reasonCode ∈ CAPTURE_RECORDED_IN_ERROR`（不该记的请款）`/ CAPTURE_AMOUNT_ERROR`（金额解析或录入错误）`/ SPLIT_DEPOSIT`（拆分到账）`/ PAYOUT_FAILURE_DISPROVED`（处理方说打款失败但银行收到了）`/ OTHER`。每种类型只接受与它相符的原因或 `OTHER`。
- 加锁顺序（ADR-0004）：资金锁 → 支付行或批次行 → 银行行（按 bank_txn_id 升序）→ 账户（账本内部按代码升序）→ 差异行。
- 差异处置：`evidence` 里引用的、以及下面每种类型列出的“自动关闭”差异，若当时为 `OPEN`，同一事务转 `RESOLVED`，`resolution_correction_id` = 本次纠错，状态历史记 OPERATOR 转换；非 `OPEN` 的不动。批次主体的差异只有在本次纠错让批次真正前进（`CONFIRMED` 或 `BANKED`）时才自动关闭。
- 幂等：没有幂等键；靠自然约束。重复提交同一纠错得到 409（`ALREADY_REVERSED`、`BATCH_NOT_AWAITING_DEPOSIT` 等），响应体带已有的 `correctionId`。4xx 一律不留任何行。
- 纠错接口属于运营接口，公开演示配置不注册（ADR-0014、ADR-0017），开关 `ledgerlab.corrections.enabled`，默认 true。

### 2. 四种类型

**① `VOID_CAPTURE` 撤销误记的请款**（AC-6.3、6.4、6.6、6.8）
- 输入：`originalEntryId` = 该支付当前生效的金额分录（`PAYMENT_CAPTURED`，或之前 `RESTATE_CAPTURE_AMOUNT` 留下的、还没被冲销的 `CORRECTION`）。
- 前置（按此顺序报错）：分录不存在 404 `ENTRY_NOT_FOUND`；是 `REVERSAL` → 409 `CANNOT_REVERSE_REVERSAL`；已作为目标 → 409 `ALREADY_REVERSED`；不是上述金额分录 → 409 `ENTRY_NOT_CORRECTABLE`；支付 `SETTLED` 或已有 `batch_id` → 409 `USE_REFUND_FOR_SETTLED_PAYMENT`；`refunded_amount > 0` → 409 `PAYMENT_HAS_REFUNDS`。
- 分录：`REVERSAL`（借 2101 G / 贷 1101 G）。
- 业务：支付 `CAPTURED → VOIDED`，状态历史 `evidence_type=correction`、`evidence_id=cor_…`；`amount`、`expected_fee` 不变（保留原记录）。
- 退款额度：`VOIDED` 不可受理退款（已有 `PAYMENT_NOT_REFUNDABLE`）。
- 之后：同一 `charge.succeeded` 重放仍是 `DUPLICATE_FACT`，不会重新请款；若某批次又出现这笔支付，批次 `HELD(PAYMENT_STATE_CONFLICT)`、开 `BATCH_STATE_CONFLICT`（说明撤销本身可能错了，P4 不支持恢复 `VOIDED`，留给人工）。

**② `RESTATE_CAPTURE_AMOUNT` 修正未结算支付的金额**（AC-6.5、AC-1.4d 样例 E）
- 输入：`originalEntryId`（同①）、`newAmount`。前置同①的前五条，另外：`newAmount` 不在 50～99999999 → 400；等于当前金额 → 409 `AMOUNT_UNCHANGED`；小于 `refunded_amount` → 409 `AMOUNT_BELOW_REFUNDED`；`evidence` 至少含一个 `issue`、`statement_line`、`processor_fetch` 或 `inbox_event`。
- 分录：`REVERSAL`（借 2101 旧 / 贷 1101 旧）＋ `CORRECTION`（借 1101 新 / 贷 2101 新，`source_key=correction:{cor_id}`，subject = 该支付）。
- 业务：`core.payment.amount = newAmount`，`expected_fee` 按合同费率重算；状态历史追加一行 `CAPTURED → CAPTURED`（证据为纠错）。
- 退款额度：剩余可退 = 新金额 − `refunded_amount`，立即生效；已受理的退款不动。
- 批次重核：同一事务里，对所有 `HELD(AMOUNT_MISMATCH)` 且固定明细存档含这笔支付的批次，按存档重新核对（不发 HTTP，复用 P2 T06 的 UNKNOWN_PAYMENT 重核路径）。通过 → `HELD → RECEIVED → CONFIRMED`、记 `BATCH_SETTLED`、唤醒银行 `WAITING` 行、批次差异自动关闭；仍不通过 → 批次保持 `HELD`（原因按新结果刷新，追加历史），差异保持 `OPEN`，纠错照样提交。

**③ `APPLY_UNAPPLIED_CASH` 待查收款归位到批次（含拆分到账）**（新 AC-6.10）
- 输入：`batchId`、`bankTxnIds`（1～10 个，不重复）。
- 前置：批次不存在 404；批次不是 `CONFIRMED`（或已有匹配入账）→ 409 `BATCH_NOT_AWAITING_DEPOSIT`；任一流水当前版本不是 `BOOKED`/`CREDIT`/`CAD`/`UNAPPLIED` → 409 `BANK_LINE_NOT_UNAPPLIED`；任一流水 `reference ≠ batchId` → 409 `BANK_LINE_REFERENCE_MISMATCH`；Σ 金额 ≠ 批次 `net` → 409 `APPLICATION_AMOUNT_MISMATCH`（零容差，不支持部分应用）。
- 分录：只有一个 `CORRECTION`：借 2105 N / 贷 1102 N；每笔流水的 `UNAPPLIED_CASH_RECEIVED` 分录登记为 `APPLIED` 目标。1103 不变（钱早已在账上）。
- 业务：流水 `match_status UNAPPLIED → APPLIED`、`matched_batch_id = batchId`；批次 `CONFIRMED → BANKED`（证据为纠错）。自动关闭：这些流水为 subject 的差异、该批次的 `DEPOSIT_MISSING`/`DEPOSIT_NOT_RECEIVED`/`TIMING_DIFFERENCE`。

**④ `RESTORE_FAILED_PAYOUT` 银行冲突证据恢复**（新 AC-6.11）
- 场景：批次已 `PAYOUT_FAILED`（记过 `BATCH_PAYOUT_FAILED`），银行却收到了这笔钱（入账行引用该批次，记成待查收款、开 `BATCH_STATE_CONFLICT`）。运营核实钱确实到了，处理方的失败通知是错的。
- 输入：`batchId`、`bankTxnId`。前置：批次不是 `PAYOUT_FAILED` → 409 `BATCH_NOT_PAYOUT_FAILED`；流水条件同③且金额 = 批次 `net`；这次失败打款已经作为 `PAYOUT_FAILURE` 退回行出现在别的批次里 → 409 `FAILED_PAYOUT_ALREADY_RETURNED`（这时处理方实际上付了两次，正确做法是把多的钱退回，走下面第 3 节的退还）。
- 分录：`REVERSAL`（冲销 `BATCH_PAYOUT_FAILED`：借 1102 N / 贷 1101 N）＋ `CORRECTION`（借 2105 N / 贷 1102 N）；该流水的 `UNAPPLIED_CASH_RECEIVED` 登记为 `APPLIED` 目标。
- 业务：批次 `PAYOUT_FAILED → BANKED`（新边，只能由此纠错触发）；流水 `APPLIED`。之后若有批次带着这笔失败打款的退回行，因为原批次已不是 `PAYOUT_FAILED`，按现有规则 `HELD`。自动关闭：该批次的 `BATCH_STATE_CONFLICT`、该流水为 subject 的差异。

### 3. 待查收款退还（不是纠错，是银行证据驱动的业务事件，AC-6.9）

银行导入时，`BOOKED`、`DEBIT`、`CAD` 的出账行，若 `reference` 等于某条当前 `UNAPPLIED` 入账流水的 `bank_txn_id` 且金额相等：记 `UNAPPLIED_CASH_RETURNED`（借 2105 X / 贷 1103 X，`source_key=bank_txn:{出账流水}`），入账流水 `UNAPPLIED → RETURNED`，出账流水 `match_status = RETURN_POSTED`；以入账流水为 subject 的 `OPEN` 差异转 `RESOLVED`，`explanation` 写明出账流水号（没有纠错号）。金额不等、引用的流水不是 `UNAPPLIED`：沿用 P2 规则（`UNMATCHED_NO_POSTING`、开 `UNMATCHED_BANK_LINE`），不支持部分退还。银行余额核对（INV-11）的 1103 汇总加入 `UNAPPLIED_CASH_RETURNED`，按出账行 `booking_date` 归期。

### 4. HELD 批次的运营重核（不是纠错）

`POST /api/batches/{batchId}/recheck`：对 `HELD` 批次（`UNSUPPORTED_PAYOUT` 除外）按固定明细存档、用当前本地记录重新核对，规则与自动重核相同。用途：P2 时因退款行 `HELD(UNSUPPORTED_LINE)` 的历史批次（P3 已支持退款行）、以及纠错后的人工复核。批次不是 `HELD` → 409 `BATCH_NOT_HELD`。通过就确认记账，不通过就刷新原因（新 AC-6.12）。

### 5. 明确不支持

- 冲销任何其他分录：退款四种、`BATCH_SETTLED`、`BANK_DEPOSIT_RECEIVED`、`UNAPPLIED_CASH_*`、提现类 → 409 `ENTRY_NOT_CORRECTABLE`。退款和提现的问题用它们自己的业务事件。
- 已结算支付的金额或撤销：`USE_REFUND_FOR_SETTLED_PAYMENT`。
- 恢复 `VOIDED` 支付、撤销一次纠错（冲销 `REVERSAL`）：不支持。改金额后又发现错，可以再做一次 ②（目标是上次的 `CORRECTION`）。
- 手续费差异：按处理方实际值结算，`FEE_MISMATCH` 由运营 `ACCEPTED`，不纠错。
- 部分应用、部分退还、跨批次应用待查收款；自动应用待查收款。
- 提现相关的冲突证据（`PAYOUT_STATE_CONFLICT`）：P5 再定。

### 6. 不变量补充

- INV-9 补：2105 余额 = Σ 当前版本 `match_status = UNAPPLIED` 的入账流水金额（INV-12）。
- INV-9 的 2101 = Σ `CAPTURED` 支付的**当前** `amount`（改金额后按新值）。
- 模型测试（ADR-0018）加入四种纠错、退还、重核动作，参考模型独立实现上面的前置与效果（新 AC-6.13）。

### 7. 表结构与授权（P4 迁移）

- 新表 `ledger.correction`（`id cor_…`、`correction_type`、`reason_code`、`reason_note`、`evidence_refs` jsonb 非空数组、`params` jsonb（旧/新金额、批次号、流水号）、`subject_type`、`subject_id`、`requested_by`、`recorded_at`）与 `ledger.correction_target`（`correction_id`、`entry_id` UNIQUE、`role`）。`app` 只 INSERT/SELECT，改删清空由触发器拒绝；`readonly` 只读视图。原设计的 `original_entry_id` 单列由目标表取代。
- 补外键：`journal_entry.correction_id → ledger.correction`，`recon.issue.resolution_correction_id → ledger.correction`。
- `core.payment` 给 `app` 增加 UPDATE(`amount`, `expected_fee`)；只能经纠错改的约束靠应用层＋模型测试守，数据库保证 `refunded_amount ≤ amount` 与金额范围。
- `recon.bank_line.match_status` 增加 `APPLIED`、`RETURNED`、`RETURN_POSTED`，CHECK 相应放宽（`APPLIED` 要求 `matched_batch_id`，`RETURN_POSTED` 只限 `DEBIT`）。
- 批次 `hold_reason` 增加 `PAYMENT_STATE_CONFLICT`。
