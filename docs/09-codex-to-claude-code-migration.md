# Codex → Claude Code 多 Agent 配置迁移说明

## 迁移日期

2026-08-09

## 迁移概览

| Codex 文件 | Claude Code 文件 | 说明 |
|-----------|-----------------|------|
| `.codex/config.toml` | `.claude/settings.local.json` | Agent 全局配置迁移 |
| `.codex/agents/architect.toml` | `.claude/agents/architect.md` | 架构分析 Agent |
| `.codex/agents/implementer.toml` | `.claude/agents/implementer.md` | 实现 Agent |
| `.codex/agents/reviewer.toml` | `.claude/agents/reviewer.md` | 代码审查 Agent |
| `.codex/agents/tester.toml` | `.claude/agents/tester.md` | 测试 Agent |
| `docs/08-multi-agent-collaboration.md` | `.claude/skills/multi-agent.md` | 协作流程技能 |
| （Codex 并行审查流程） | `.claude/workflows/multi-agent-review.js` | 自动化并行审查工作流 |

## 配置字段映射

### Agent 配置

| Codex 字段 (TOML) | Claude Code 字段 (YAML frontmatter) | 备注 |
|-------------------|-------------------------------------|------|
| `name` | `name` | 直接映射 |
| `description` | `description` | 直接映射 |
| `developer_instructions` | 正文 (Markdown) | Codex 用 TOML 字符串，Claude Code 用 Markdown 正文 |
| `model_reasoning_effort` | `reasoningEffort` | `"high"` → `high`, `"medium"` → `medium` |
| `sandbox_mode` | `tools` | `"read-only"` → `Read, Glob, Grep, WebFetch`; `"workspace-write"` → 加上 `Edit, Write, Bash` |
| `nickname_candidates` | N/A | Claude Code 不支持别名候选，通过 `name` 直接引用 |

### 全局设置

| Codex 字段 | Claude Code 等效 | 说明 |
|-----------|-----------------|------|
| `agents.enabled = true` | 无需配置 | Claude Code Agent 始终可用 |
| `max_concurrent_threads_per_session = 4` | Workflow 内置 | 默认最多 16 个并行 Agent |
| `max_depth = 1` | workflow() 限制 | 仅允许 1 层嵌套 |
| `interrupt_message = true` | 默认行为 | 中断时自动写入消息 |
| `job_max_runtime_seconds = 1800` | 默认超时 | Agent 有内置超时控制 |

## 使用方式对比

### Codex
```text
请使用项目配置的多 Agent 协作完成"排行榜缓存优化"。
```

### Claude Code
```text
请使用多 Agent 协作完成"排行榜缓存优化"。
（加载 /multi-agent 技能，或直接使用 Agent 工具指定 agentType）
```

或者直接调用：
```
/multi-agent 请使用项目多 Agent 协作完成"排行榜缓存优化"
```

## 关键差异

1. **Agent 调用方式**：Codex 用 `spawn_agent` 工具，Claude Code 用 `Agent` 工具指定 `agentType`。
2. **工作流编排**：Codex 靠主 Agent 手动编排，Claude Code 额外提供 `Workflow` 工具做自动化流水线（`pipeline`/`parallel`）。
3. **权限继承**：Codex 父会话权限覆盖子 Agent 默认沙箱；Claude Code Agent 的 `tools` 字段直接限制可用工具集。
4. **别名**：Codex 支持 `nickname_candidates`，Claude Code 通过 `name` 直接引用。
5. **技能系统**：Claude Code 额外提供 `Skill` 机制（`.claude/skills/`），用于加载可复用的协作指令。

## 原始 Codex 文件保留

`.codex/` 目录中的原始配置文件**已保留**，以便：
- 在 Codex 中继续使用原有 workflow
- 作为配置参考和回退方案
- 供未来同步更新

## 验证方式

1. 确认 `.claude/agents/` 下 4 个 Agent 文件存在且格式正确。
2. 使用 `/multi-agent` 加载协作技能。
3. 通过 Agent 工具指定 `agentType: "architect"` 启动只读分析。
4. 使用 `Workflow` 工具调用 `multi-agent-review` 运行并行审查。
