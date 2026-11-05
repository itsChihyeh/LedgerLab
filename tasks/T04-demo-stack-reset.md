# P7-T04 本地演示栈、种子与每日重置（AC-9.5、AC-9.8）

## 背景
demo-and-delivery.md 第 1、5 节；`compose.yaml`、`compose.sandbox.yaml`、`scripts/sandbox/*`、`scripts/smoke-common.sh`（风格）。

## 范围
1. 前端构建产物打进 app jar 的 `static/`（Maven 里调用 `npm ci && npm run build` 只在显式 profile，例如 `-Pwith-frontend`，默认构建和 CI 不变；或者在演示镜像的 Dockerfile 多阶段构建里做——选一种，写进最后输出）。demo profile 下 app 在 `/` 提供页面。
2. `compose.demo.yaml`：项目名 `ledgerlab-demo`，端口 28080/28081/25432 只绑 127.0.0.1，数据库名 `ledgerlab_demo`；数据库密码由 `scripts/demo/up.sh` 首次随机生成存 `.demo/secrets.env`（gitignore），不读 `.env`；simulator 不带任何 Stripe 密钥（fake Stripe 用 compose 里公开的测试假值，和冒烟一样）。
3. `scripts/demo/up.sh`（构建、起栈、等健康、调 `/api/demo/seed`、调不变量接口确认全 ok）、`down.sh`（`down -v`，演示数据本来就每天重置）、`status.sh`、`reset.sh`（第 5 节：先存档 summary 到 `.demo/archive/<UTC 日期>.json` 和可配置目录，再 down -v、up、seed、确认不变量）。
4. 演示页“上次重置时间”和最近 7 天存档：app 在 demo profile 读取一个挂载的存档目录（只读），`GET /api/demo/archive` 返回最近 7 份摘要。
5. README 命令列表加演示站几行。

## 测试与执行者验证
- `scripts/demo/selftest.py`：脚本纯函数自检。
- 实际跑：`sh scripts/demo/up.sh` → 调几次动作 → `sh scripts/demo/reset.sh` → 断言存档存在、重置后计数归零、不变量全 ok → `sh scripts/demo/down.sh`。写成 `scripts/demo/check-reset.sh` 一次性跑完（AC-9.5、AC-9.8 的证据），输出 `target/demo/check-reset-*.json`。
- 不得影响常驻的 `ledgerlab-sandbox` 项目（脚本里所有 compose 调用都显式 `-p ledgerlab-demo`）。

## 可改范围
`compose.demo.yaml`、`scripts/demo/**`、`.gitignore`、`pom.xml`（只加显式 profile）、演示镜像所需的 Dockerfile（放 `docker/demo/`）、`src/main/java/dev/ledgerlab/demo/**`、`application-demo.yaml`、README 命令列表、`docs/design/demo-and-delivery.md`。

## 完成标准
按 `_common.md` 第 5 条；`sh scripts/demo/check-reset.sh` 通过。本地提交（`Task: P7-T04`）。

## 执行者验证
亲自跑 `sh scripts/demo/check-reset.sh`，确认 `docker ps` 里 `ledgerlab-sandbox` 的容器仍在运行。
