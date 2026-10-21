---
name: adr
description: Use when a LedgerLab design decision is made or changed (a trade-off between options, a new constraint, a reversal of an earlier ADR). Writes or amends an Architecture Decision Record in docs/adr/ in the project's format.
---

# 写或修订 ADR

## 新增

1. 在 `docs/adr/` 里找最大编号，新文件为 `NNNN-<英文短横线描述>.md`（四位编号，加一）。
2. 按下面的格式写，用中文，短而具体，一般不超过一屏：

```markdown
# ADR-NNNN <一句话标题>

状态：提议 | 已采纳（阶段） | 已被 ADR-XXXX 取代。来源：<谁、在哪次讨论定的>。

## 背景
<要解决什么问题；不处理会出什么错。>

## 决定
<编号列出具体做法，写到能直接照着实现的程度。>

## 备选
- **<方案>**：<为什么不选。>

## 后果
<代价、限制、对测试和其他模块的影响；对应哪些 AC 和测试。>
```

3. 如果它影响 `docs/design/` 下的文件或 AC，同步修改并在 ADR 里注明。

## 修订已有 ADR

- 不重写历史。在原文件末尾加 `## 补充（YYYY-MM-DD，原因）` 小节说明改了什么。
- 决定被推翻时，新写一个 ADR，并把旧 ADR 的状态改为“已被 ADR-XXXX 取代”。
