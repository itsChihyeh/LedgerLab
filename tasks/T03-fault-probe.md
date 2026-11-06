# P8-T03 故障探测：每种注入在主应用里产生什么差异

## 背景
读 `docs/design/investigation-agent.md` 第 6、8.1 节（原因表里有“待探测”项）。注入能力全在 `simulator/README.md`：银行故障计划（`PUT /sim/bank/plan`，含 `memo` 字段）、payout 故障（`POST /sim/payouts/{id}/faults`：`duplicate_transaction`、`extra_line`、`fail_page`）、charge `fee` 覆盖、payout `amount` 覆盖、relay 计划（DROP、DELAY…）、银行文件 `?corrupt=`。端到端推进业务的写法照抄 `scripts/smoke/p2.py`、`p4.py`、`scripts/smoke-p5.sh`（一次性 Compose 栈、`compose.smoke.env` 的公开合成配置、历史日期与 `advance_to` 的时钟处理、处理方快照 `POST /api/recon/processor-snapshots`、银行导入 `POST /api/bank-statements`、对账运行 `/api/recon/runs`）。差异类别见 `docs/design/data-dictionary.md` 差异类别和 `docs/design/contracts.md` 老化表。

## 范围
1. `agent/eval/` 下的评测工具代码（**不在** `agent/src` 里；这里允许用 `subprocess` 调 `docker compose`，仍然只用标准库）：
   - `agent/eval/stack.py`：起停一次性栈。项目名 `ledgerlab-agent-eval-<12位随机hex>`，只用 `compose.yaml`（加需要的 smoke 叠加文件）和 `compose.smoke.env`，照 `smoke-p5.sh` 的做法禁用 `.env`（`COMPOSE_DISABLE_ENV_FILE=1`，清掉 `COMPOSE_FILE` 等变量），映射到随机空闲宿主端口；退出时（含异常、Ctrl-C）`down -v --remove-orphans`，失败时把 compose 日志存到 `target/agent-eval/`。需要的 simulator jar 不存在就明确报错，告诉人先怎么构建（照 smoke 脚本的检查方式）。
   - `agent/eval/scenario.py`：场景原语（建 charge、生成 payout、reconcile、paid、设置银行计划、推进时钟、导入银行日报、处理方快照、跑对账、列差异），每个都是对公开 HTTP 接口的薄封装，不读数据库、不读 `sim_truth`。
   - `agent/eval/probe.py`：对设计第 6 节原因表里的每种注入各跑一个最小场景（一个场景里可以放多个互不影响的 payout，以减少起栈次数），跑完列出产生的全部差异（类别、subject、状态、`expected/actual` 摘要），再把银行 memo 写成一段提示注入文字跑一次，确认 memo 会出现在哪个接口的响应里（`/api/bank-lines/{id}`？差异的 evidence？）。另外各跑一次 relay DROP 一条 `charge.succeeded`、payout `amount` 覆盖、银行文件 `corrupt`，看产生什么。需要老化才出现的差异，按 contracts 老化表把时钟推进到阈值之后再跑一次对账。
2. 输出 `docs/eval/probe-<YYYY-MM-DD>.md`（和同名 `.json`）：每种注入 → 实际出现的差异类别（可能多条）、触发需要的步骤（是否要老化、要推进多久）、memo 出现在哪些响应字段里；注入后**没有**产生差异的项明确列出。报告不得包含任何密钥或看起来像密钥的字符串（仓库有 gitleaks）。
3. 回写 `docs/design/investigation-agent.md` 第 6 节：加“实现备注（P8-T03）”，给出定稿的原因表（保留能产生差异的项、写明实际类别；不能产生差异的项删掉并说明）。不改 `routes.json` 或枚举以外的设计决定；如果探测发现必须新增原因值或改枚举，同步修改 `agent/src/ledgerlab_agent/` 里的枚举和受影响的测试。
4. 一个入口脚本 `scripts/agent/probe.sh`（POSIX sh，Git Bash 能跑）：检查 docker、python，调用 `python agent/eval/probe.py`。

## 关键测试场景
- `agent/tests/` 加不起 Docker 的单元测试：`stack.py` 生成的项目名前缀与随机性、compose 命令里没有 `.env`、端口解析；`scenario.py` 的请求构造（用假 HTTP 后端）。方法名带 `ac_10_5`。
- 实际跑一次 `sh scripts/agent/probe.sh` 产出报告（这一步需要 Docker；跑的时间写进最后输出）。

## 可改范围
`agent/eval/**`、`agent/tests/**`、`agent/src/ledgerlab_agent/`（仅枚举与对应测试）、`scripts/agent/**`（新建）、`docs/eval/**`（新建）、`docs/design/investigation-agent.md`、`.gitignore`。**不改** `src/`、`simulator/`、`frontend/`、现有 `scripts/` 文件、Compose 文件（需要改 Compose 文件时停下来问；只用已有的叠加文件）。

## 完成标准
单元测试全绿；`docs/eval/probe-*.md` 已生成并提交；设计第 6 节定稿；提交带 `Task: P8-T03`。
