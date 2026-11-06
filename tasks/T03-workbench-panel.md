# P9-T03 工作台“调查助手”面板与采纳表单

## 背景
读 `docs/design/agent-workbench-and-mcp.md` 第 2.2、2.3、3 节，ADR-0020，AC-11.3、AC-11.4、AC-11.5，ADR-0011 第 1 节（纠错请求必填项）和第 2 节③（`APPLY_UNAPPLIED_CASH` 的前置与 409 码）。看 `frontend/src/pages.tsx` 的 `IssuePage` 与 `AcceptIssue`、`frontend/src/components.tsx`（`Resource`、`Field`、`Table`、`href`、`enc`）、`frontend/src/api.ts`、`frontend/src/demo-model.ts`＋`demo.test.tsx`（纯函数＋`renderToStaticMarkup` 的测试写法；本项目前端单测**没有** jsdom 和 testing-library，不许加）、`frontend/vite.config.ts`、`src/main/java/dev/ledgerlab/corrections/internal/CorrectionJson.java`（纠错请求的 JSON 字段名）。调查服务（P9-T01）已在 `agent/service/`，记录形状以它的实现和设计第 2.3 节实现备注为准。

## 范围
1. `frontend/src/agent-model.ts`（纯函数，无 React、无 fetch）：
   - 调查记录与健康检查的 TypeScript 类型（与 T01 实际输出一致）；
   - `citationHref(citation)`：设计第 3.1 节的映射，`run` 和未知种类返回 `null`；
   - `adoptionPlan(run, issue)`：返回 `{kind:"apply_unapplied_cash", batchId, bankTxnIds, evidence, reasonCode, note}`、`{kind:"accept", reason}`，或 `{kind:"none", why}`（`why` 是固定的几种：差异非 OPEN、非 ok、拒答、有核验不通过的引用、原因无可采纳操作、引用不足以预填），严格按第 3.2 节的条件和顺序；
   - `correctionRequest(plan, requestedBy, note)`、`acceptRequest(plan, reason)`：组装发给已有接口的请求体（字段名以 `CorrectionJson` 为准）；`note`／`reason` 截到 2000 个字符（按码点）；`requestedBy` 去首尾空白后必须非空。
2. `frontend/src/agent.tsx`：`AgentPanel`（`<section aria-label="调查助手">`）和两张采纳表单。行为按第 3.1、3.2 节：先查 `/agent/health`；不可达显示“调查助手未启动”；列出历次调查（最近一次展开）；“让 Agent 调查”按钮；固定的“本地替身（规则基线），不代表大模型能力”与“结论由 Agent 给出，未经人工确认前不改变任何数据”；引用链接与“未在本次调查中读到”；点“采纳”只在本地展开表单（**不发请求**）；提交后成功调用 `onChanged()` 让 `IssuePage` 刷新，失败显示后端 `code` 或状态码。原因和动作的中文名放在 `agent-model.ts` 的常量表里（13 种原因＋`INSUFFICIENT_EVIDENCE`，6 种动作，以 `agent/src/ledgerlab_agent/enums.py` 为准）。
3. `IssuePage` 在“关联证据”之后挂 `AgentPanel`；不改动已有“运营处置／接受”表单的行为。
4. `vite.config.ts` 加 `/agent` → `http://localhost:8097` 代理（端口可用环境变量 `VITE_AGENT_PORT` 覆盖，E2E 会用到）。
5. 样式加进 `style.css`（与现有风格一致，不引入新库）。回写设计第 3 节实现备注。

## 关键测试场景（`frontend/src/agent.test.tsx`，Vitest）
- `ac_11_3 …`：`citationHref` 覆盖全部 12 种 kind（`batch_line`、`statement_line` 的复合 id 拆分与编码）；`renderToStaticMarkup(<RunView …/>)`（或你拆出的纯展示组件）对一份 SPLIT 调查记录：含原因中文名与枚举、解释文本、SOP 条款、工具调用数、替身提示；核验不通过的引用渲染为“未在本次调查中读到”且没有 `href`；解释里放 `<script>` 字样时输出被转义。
- `ac_11_4 …`：`adoptionPlan` 每一种 `none` 原因各一个用例；SPLIT 正常 → `apply_unapplied_cash`，`bankTxnIds` 排好序、`evidence` 含 issue 和每条 bank_txn、备注以 `采纳调查 inv_…（stub）：` 开头；SPLIT 引用两个批次或只有一条流水 → `none`；FEE → `accept`；其他 11 种原因 → `none`；`correctionRequest` 的请求体逐字段断言（`type`、`batchId`、`bankTxnIds`、`reasonCode`、`reasonNote`、`evidence`、`requestedBy`），`requestedBy` 为空白时报错，2001 字的备注被截到 2000。
- `ac_11_5 …`：健康检查失败时的展示组件输出“调查助手未启动”。
- 已有前端测试全部仍通过。

## 可改范围
`frontend/src/agent-model.ts`、`frontend/src/agent.tsx`、`frontend/src/agent.test.tsx`（均新建）、`frontend/src/pages.tsx`（只在 `IssuePage` 里挂面板、必要的 import）、`frontend/src/api.ts`（只加类型，若你更愿意放这里）、`frontend/src/style.css`、`frontend/vite.config.ts`、`docs/design/agent-workbench-and-mcp.md`。**不改** `agent/**`、`src/**`、E2E 文件。

## 完成标准
`cd frontend && npm run check` 全绿（写出测试数）；`npm run build` 成功。另外手动验证一次：在仓库根起调查服务的回放模式 `python agent/ledgerlab-agent-service.py --snapshot agent/eval/data/dev/snapshot.json --model stub --port 8097 --store <临时文件>`（用完按 PID 停掉），再 `npm run dev` 只为确认 `/agent/health` 能经 Vite 代理拿到（用 `node` 或 `python` 发请求即可；主应用不在时页面其余部分会报错，这是预期的，不用管）。提交带 `Task: P9-T03`。
