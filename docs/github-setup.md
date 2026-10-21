# GitHub 仓库设置（推到 GitHub 后做一次）

仓库公开后，以下功能对公开仓库免费。

## 1. 保护 main（规则集）

Settings → Rules → Rulesets → New ruleset → New branch ruleset：

1. Ruleset name 填 `protect-main`，Enforcement status 选 **Active**。
2. Target branches → Add target → **Include default branch**。
3. 勾选 **Restrict deletions** 和 **Block force pushes**。
4. 其他规则先不勾。只有一个人开发时，“必须走 PR”和“必须通过状态检查”会挡住直接推送到 main；以后改成 PR 流程再加上 CI 的三个检查（`Secrets, migrations, skills`、`Backend (build, tests, checks)`、`Frontend (lint, types, tests)`）。
5. Bypass list 留空，然后 Create。

验证：在本地执行 `git push --force origin main`，应该被拒绝。

## 2. 密钥和依赖

Settings → Advanced Security（旧界面叫 Code security and analysis）：

- 打开 **Secret scanning** 和 **Push protection**：推送里含密钥（包括 Stripe 密钥）时，GitHub 直接拒绝这次推送。
- 打开 **Dependabot alerts**。`.github/dependabot.yml` 已经配置了 Actions 的版本更新。
