---
name: multi-agent
description: 触发多 Agent 协作流程，按"并行分析 → 主 Agent 汇总 → 单写者实现 → 测试与审查 → 集成"的标准流水线处理复杂任务。
model: fable
---

# 多 Agent 协作技能

本技能实现了 Codex 多 Agent 标准流程到 Claude Code 的迁移。
原始配置见 `.codex/` 目录（config.toml + agents/*.toml），操作手册见 [docs/08-multi-agent-collaboration.md](docs/08-multi-agent-collaboration.md)。

## 可用 Agent

| Agent | 类型 | 沙箱 | 写生产代码 | 用途 |
|-------|------|------|-----------|------|
| `architect` | 只读分析 | Read-only | 否 | 需求、调用链、影响范围、事务/Redis/并发分析 |
| `implementer` | 单写者 | Read+Write | 是 | 按唯一方案受控修改生产代码 |
| `reviewer` | 只读审查 | Read-only | 否 | 正确性、安全、事务、并发、回归审查 |
| `tester` | 测试 | Read+Write | 否 | 测试缺口分析、补充测试、运行验证 |

## 标准流程

### 第一步：并行只读分析

使用 Agent 工具并行启动 `architect` 和 `reviewer`（如涉及事务/并发/安全）：

```
- architect: 只读分析需求、真实调用链、影响文件、数据库、Redis、事务、权限和测试范围。
- reviewer: 只读扫描事务、并发、安全风险。
```

**关键约束**：分析阶段禁止修改文件。等待两个 Agent 全部完成后再进入第二步。

### 第二步：主 Agent 汇总方案

主 Agent 阅读分析结果，汇总为唯一实施方案，明确：
- 文件范围与文件所有权
- 停止条件与验收标准
- 测试范围

**方案必须经用户确认后再进入实现阶段。**

### 第三步：单写者实现

使用 Agent 工具启动一个 `implementer`，传入已确认的方案。
同一时刻只允许一个 Agent 写入生产代码。
强关联的 Controller、Service、Mapper、SQL 和文档归同一 Agent。

### 第四步：测试与审查（并行）

使用 Agent 工具并行启动 `tester` 和 `reviewer`：

```
- tester: 补充测试、运行验证。
- reviewer: 只读审查最终差异。
```

### 第五步：主 Agent 集成

主 Agent 处理阻塞性问题，运行最终测试，按项目规则提交和推送。

## 文件所有权规则

- 同一时刻不得让两个 Agent 修改同一文件。
- `architect` 和 `reviewer` 只能读取，不得修改。
- `tester` 原则上只修改 `src/test` 或前端测试文件。
- 子 Agent 默认不执行 `git commit` 或 `git push`；由主 Agent 在测试通过后提交。

## 边界与停止条件

多 Agent 委派不得：
- 扩大用户需求
- 绕过新增依赖、数据库结构变更、跨模块修改的确认要求
- 把前端任务队列自动推进扩展到后端/SQL/Redis

任何 Agent 发现以下情况必须停止并报告证据：
- 工作区冲突
- 业务规则不明确
- 测试失败
- 文档与代码冲突

## 使用方式

### 标准开发提示词

```
请使用项目配置的多 Agent 协作完成"<功能名称>"。

1. 让 architect 只读分析需求、真实调用链、影响文件、数据库、Redis、事务、权限和测试范围。
2. 如果涉及事务、并发或安全，同时让 reviewer 只读扫描风险。
3. 等待分析完成，由主 Agent 汇总唯一实施方案、文件所有权和停止条件。
4. 只让一个 implementer 按方案修改生产代码；任何时刻不得让两个 Agent 修改同一文件。
5. 实现完成后，让 tester 补充并运行测试，同时让 reviewer 只读审查最终差异。
6. 主 Agent 处理阻塞性问题，运行最终相关测试，并按 AGENTS.md 提交、推送和总结。

所有 Agent 必须保留现有未提交修改，不新增依赖，不擅自修改数据库结构或扩大模块范围。
```

### 并行只读审查提示词

```
请并行使用只读 Agent 审查当前分支相对 main 的改动：

- reviewer A：事务、数据库更新行数、唯一约束和并发；
- reviewer B：Redis Key、TTL、缓存降级和 MySQL 一致性；
- reviewer C：JWT、角色权限、资源归属、路径和输入安全；
- tester：只读分析测试缺口，不修改文件。

等待全部完成后，由主 Agent 按 P0、P1、P2 汇总。
每项发现必须包含文件、方法、触发条件、影响、证据和最小修复建议。
```

## 注意事项

1. Agent 共享同一工作区，包括用户尚未提交的修改。
2. 委派前后都要运行 `git status`。
3. 不使用无选择的 `git add .`；主 Agent 必须显式暂存本任务文件。
4. 简单任务不强制使用所有角色，避免无意义令牌消耗。
5. 两个独立功能确需并行写入时使用独立 Worktree。
