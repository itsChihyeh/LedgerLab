# P9-T01 调查服务：本机 HTTP 服务、只追加存储、引用核验

## 背景
读 `docs/design/agent-workbench-and-mcp.md` 第 1、2、5 节，ADR-0020，AC-11.1、AC-11.2。看 P8 的 `agent/src/ledgerlab_agent/cli.py`（怎么组装 transport、`ToolRegistry`、`SopSearch`、基线和替身）、`agent.py`（`investigate()`、`Investigation`）、`verify.py`（`verify()`）、`sop.py`（`required_evidence_by_cause()`）、`agent/ledgerlab-agent.py`（启动器，以及 eval 为什么放在包外）、`agent/tests/test_static.py`。

## 范围
1. 新包 `agent/service/ledgerlab_agent_service/`（`__init__.py`、`app.py`（HTTP 处理）、`store.py`（JSONL 只追加存储）、`runner.py`（组装并跑一次调查、生成第 2.3 节的调查记录）、`__main__` 或 `cli.py`），启动器 `agent/ledgerlab-agent-service.py`（与 `ledgerlab-agent.py` 同样的薄启动器，把 `agent/src` 和 `agent/service` 放进 `sys.path`）。
2. 第 2.1 节的参数：`--base-url` 与 `--snapshot` 二选一；`--model stub|baseline|scripted:<path>`（复用 `cli.build_model` 与基线的组装方式，不复制规则）；`--host`（只接受回环，默认 `127.0.0.1`）、`--port`（默认 8097；`0` 表示随机端口，启动后在 stderr 打一行 `listening on http://127.0.0.1:<port>`，测试和 E2E 用它）；`--store`（默认 `agent/.runs/investigations.jsonl`，父目录不存在就创建）。启动参数错误退出 2。
3. 第 2.2 节全部接口和错误码；第 2.3 节的调查记录形状（camelCase 键）；`verification` 用 P8 的 `verify()`；`status=error` 时的拒答结论与短码（后端连不上记 `backend_unreachable`，其他意外记 `internal_error`；任何时候都不把异常原文、堆栈或 base URL 写进响应和存储，只写 stderr）。
4. `runId` = `inv_` + 26 位 Crockford base32（时间 48 位＋随机 80 位，ULID 写法），时钟和随机源可注入。
5. 存储：只追加 JSONL，一行一条记录；启动时读入；坏行跳过并在 stderr 记一行；`GET ?issueId=` 新的在前、最多 20 条。
6. 并发：进程内一个非阻塞锁，第二个 POST 立即 409 `busy`。
7. `agent/service/tests/`：服务测试与第 2.4 节的服务包静态检查；把 `agent/.runs/` 加进根 `.gitignore`。
8. 回写设计第 2 节实现备注（文件布局、错误短码、实际的静态检查规则）；`agent/README.md` 加“调查服务”一小节（怎么启动、接口一览、它不写主应用）。

## 关键测试场景（`agent/service/tests/`，用 `--snapshot agent/eval/data/dev/snapshot.json` 回放和 `stub`，不需要后端）
- `test_ac_11_1_…`：服务在随机端口起在线程里（或子进程），对 dev 里一条拆分到账的差异（从 `agent/eval/data/dev/labels.json` 取一条 `BANK_SPLIT_DEPOSIT` 题）`POST` → 201，记录里 `conclusion` 与 `python agent/ledgerlab-agent.py investigate … --model stub --json` 的结论**逐字段相同**，`verification.fabricatedCitations` 为空、`supported=true`，`modelIsStandIn=true`；随后 `GET ?issueId=` 和 `GET /{runId}` 能取回；重启服务（同一个 store 文件）后仍能取回。
- 错误码：非 `application/json` → 415；坏 JSON、多余字段、坏 `issueId` → 400；> 4 KB → 413；未知路径 404；`PUT`/`DELETE`/`OPTIONS` → 405；所有响应都没有 `Access-Control-Allow-*` 头。
- 并发：用一个会阻塞的 `ScriptedModel`／假模型（测试里注入）让第一次调查卡住，第二个 POST 得到 409，放开后第一次正常完成。
- `--host 0.0.0.0`、`--host 192.168.1.10` → 退出 2；`--base-url` 带凭证 → 退出 2。
- 后端不可达（`--base-url http://127.0.0.1:<一个关掉的端口>`）→ 201，`status=error`、`error=backend_unreachable`、结论是拒答；响应和存储里不含该 URL。
- 存储：坏行被跳过；记录只追加（第二次写不改变第一行的字节）。
- 静态检查（AC-11.2）：服务包只导入标准库、`ledgerlab_agent` 和自身；不出现 `urllib`、`http.client`、`socket`、`subprocess`、数据库驱动、`ledgerlab_agent.transport` 的私有名；写文件只允许 `store.py` 里一处 `"a"` 模式的 `open`；`POST` 字样只允许 `do_POST` 一处。
- P8 的 `agent/tests` 全部仍通过，`eval.sh --check` 不变。

## 可改范围
`agent/service/**`（新建）、`agent/ledgerlab-agent-service.py`（新建）、`agent/README.md`、根 `.gitignore`（只加一行）、`docs/design/agent-workbench-and-mcp.md`。`agent/src/ledgerlab_agent/` 只允许为复用而做的向后兼容的小改动（例如把 CLI 里组装 transport／tools 的代码抽成一个公开函数供服务调用，CLI 行为不变），改了要在最后输出列出。

## 完成标准
`python -m unittest discover -s agent/tests -t agent` 与 `python -m unittest discover -s agent/service/tests -t agent/service` 全绿；`sh scripts/agent/eval.sh --check` 退出 0 且工作区无新改动；手动起一次服务（回放＋stub），用 `python` 标准库发一个 POST 证明能用，把命令和输出写进最后输出。提交带 `Task: P9-T01`。
