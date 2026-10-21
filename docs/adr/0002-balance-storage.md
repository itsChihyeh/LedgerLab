# ADR-0002 账户余额存在账户行上

状态：已采纳（P0）。

## 背景
余额可以每次从 posting 汇总，也可以存在账户行上并在记账事务里更新。

## 决定
存在 `ledger.account.balance`，与 posting 在同一事务里更新；每条 posting 记下 `account_version` 和 `balance_after`。INV-2 由测试和定时任务从 posting 重算核对。

## 备选
- 实时汇总：不会不一致，但提现、退款门槛需要在锁内读余额，汇总要扫全表；而且没有一行可以锁。
- 物化视图：刷新不在事务内，不能用于门槛判断。

## 后果
账户行成为热点行和锁点（单商户下可接受，正好用来串行化退款与提现，见 ADR-0004）。参考：Square Books、pgledger。
