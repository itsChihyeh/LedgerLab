# T05 审查意见（云端，2026-11-03，不需要重跑）

1. 第一次 FAIL（EVENT_PAYMENT_ID_MISMATCH）：看过 5f0cc82，现在的判据是事件 subject_id == charge 编号，仍然要求 9 个不同 payment、每个恰好一条 capture 分录、每个事件只有一次 NEW——这是改正脚本的错误比较，不是放宽。接受。T08 的验收清单里如实写“首次运行因脚本判据错误 FAIL，修正后两次 PASS 复用同一批事件”。
2. catchup 首次补回 1 个漏收：接受。请以后 catchup 结果文件记录补回和仍缺失的事件编号（T08 顺手改，`catchup-*.json` 加 `recovered_event_ids`）。
3. T06 按 answers/T01.md 第 3 条口径直接重跑：同意。
