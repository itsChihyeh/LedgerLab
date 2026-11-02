# T01 模拟银行出站付款接口（模拟器）

## 背景
ADR-0006 补充（2026-11-02）第 3 节是正本；contracts 4.1（银行 CSV）；数据字典模拟器 `sim_bank` 各节；现有 `simulator/.../sim/bank/Bank.java`（入账、计划表、流水、日历）。

## 范围
1. 模拟器新迁移 V6：`sim_bank.outgoing_payment`（`request_id` PK、`payment_id bpm_…` UNIQUE、`amount`（1～99999999）、`currency`、`destination`、`status`（ACCEPTED/EXECUTED/REJECTED/RETURNED）、`reason` NULL、`execution_date`、`return_due_date` NULL、`plan jsonb` 冻结、`recorded_at`）；计划表允许 `target_ref` 为 `mpo_…`，扩展故障枚举：`TIMEOUT`、`EXECUTE_DROP_RESPONSE`、`UNAVAILABLE`（parameter = 次数）、`REJECT`（memo = 原因码）、`REJECT_ON_QUERY`、`EXECUTE_AFTER_REJECT`、`RETURN_AFTER_DAYS`（parameter = 工作日）。权限同其他 sim 表（只有模拟器自己能访问）。
2. `POST /sim/bank/payments`、`GET /sim/bank/payments/{requestId}`、`GET /sim/bank/payments`：去重、响应形状、状态码按 ADR 第 3 节。
3. 执行与退回挂到现有模拟时钟推进/日终处理：执行日写 DEBIT 流水（`bt_{requestId}_1`，reference=requestId），余额不足则 REJECTED(INSUFFICIENT_FUNDS) 无流水；退回在执行后第 n 个工作日写 CREDIT（`bt_{requestId}_R`，memo `RETURN ACCOUNT_CLOSED`）。流水进入现有日报 CSV，尾合计和余额照算。
4. `TIMEOUT` 的挂起时长可配（默认 30s，测试用小值），`EXECUTE_DROP_RESPONSE` 在提交事务后中断连接（关闭响应流，不返回体）。`UNAVAILABLE n` 计数在付款接口 POST/GET 上递减，用完恢复。
5. `sim_truth` 记录请求、执行、退回与每次故障。
6. 数据字典模拟器部分补实现备注。

## AC / 测试场景（模拟器测试，真实 PostgreSQL）
- 同一 requestId 重复 POST（同内容）只有一笔付款，返回 200 和原 paymentId；内容不同 409 `REQUEST_CONFLICT`。
- 默认 ACCEPTED，推进时钟到执行日后 EXECUTED，当天日报 CSV 出现 DEBIT 行，期末余额减少；余额不足 REJECTED、无行。
- 每种故障一个测试：TIMEOUT 不落库；EXECUTE_DROP_RESPONSE 落库且执行、客户端拿到连接错误；UNAVAILABLE 3 → 前 3 次 503 后正常；REJECT → 201 REJECTED 无执行；REJECT_ON_QUERY → POST ACCEPTED、之后 GET REJECTED、永不执行；EXECUTE_AFTER_REJECT → 回复 REJECTED 但执行日仍有出账；RETURN_AFTER_DAYS 2 → 第 2 个工作日 CREDIT 退回、状态 RETURNED。
- 周末/假日执行日顺延到下一个银行工作日（用现有 BankCalendar）。
- 404 未知 requestId。

## 可改范围
`simulator/**`（含新迁移和测试）；`docs/design/data-dictionary.md`、`docs/design/contracts.md` 实现备注。不改主应用。

## 完成标准
按 `_common.md` 第 5 条（模拟器要跑 `./mvnw -B -f simulator/pom.xml verify`）；本地提交（`Task: T01`）。
