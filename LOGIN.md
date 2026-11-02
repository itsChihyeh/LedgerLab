# Stripe CLI 登录账号核对（2026-11-02 15:57，本机时间）

**结论：CLI 登录的账号与 `STRIPE_API_KEY` 是同一个测试沙盒账号。** 下一步可以按 T04 起 `stripe listen`。

都在登录模式下运行（`env.sh`，`STRIPE_CLI_USE_LOGIN=1`，子进程里去掉了 `STRIPE_API_KEY`）。

| 命令 | 事件 | 结果 |
|---|---|---|
| `events resend` | T01 的 charge.succeeded（`evt_3UXQCaF5hIinUF490brSX7aS`） | 退出 0，但返回错误 “No CLI endpoints found to retry delivery”。不算成功，也不是找不到事件 |
| `events resend` | 不存在的编号（对照） | `resource_missing`，“No such notification” |
| `events retrieve` | T01 的 charge.succeeded | 成功：`charge.succeeded`，`livemode=false`，版本 `2026-09-30.endive` |
| `events retrieve` | 不存在的编号（对照） | `resource_missing`，“No such notification” |

任务说明里的 `resend` 单独判断不了（没有 listen 运行时不会投递），所以我加了只读的 `events retrieve` 和两个对照。这个事件是 T01 用密钥建的，登录会话能读到，就是同一账号。这条事件没有实际重投，第 7 节第 11 条仍待 T04 在 listen 运行时观察。

## 给 T04 的提醒

T04 的“登录账号核对”写的是“成功即同一账号；报 No such event 则不是”。实测有三点不同，脚本照字面写会判错：

1. 找不到时的文字是 “No such notification”，不是 “No such event”。应该判 `error.code == resource_missing`。
2. `events resend` 出错时**退出码也是 0**，不能靠退出码判断，要解析输出。
3. 没有 listen 运行时，同账号的 `resend` 也返回错误（`for_stripecli`）。核对要么放在 listen 起来之后，要么用 `events retrieve`。

我没有改任务说明。
