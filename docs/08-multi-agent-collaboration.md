# Codex 多 Agent 协作操作手册

本文说明如何在本项目中启用、触发和检查 Codex Subagent 协作。配置基于 2026-07-14 核对的官方格式，适用于 Codex 桌面版、CLI 和 IDE 扩展的当前版本。

## 1. 配置目标

本项目采用“并行只读分析、单写者实现、并行测试与审查、主 Agent 集成”的流程：

```text
用户任务
  -> architect + reviewer（并行只读分析）
  -> 主 Agent 汇总唯一实施方案
  -> implementer（单写者实现）
  -> tester + reviewer（测试与只读审查）
  -> 主 Agent 修复、验收、提交并推送
```

这套配置解决的是复杂任务的职责隔离和上下文管理，不会自动创建 Git 分支，也不会替代项目已有的测试、提交和停止条件。

## 2. 文件结构

```text
.codex/
├── config.toml
└── agents/
    ├── architect.toml
    ├── implementer.toml
    ├── tester.toml
    └── reviewer.toml
```

项目配置必须位于外层 Git 仓库根目录 `.codex/`，不要放到内层 Spring Boot 工程 `campus-resource-platform/.codex/`。

## 3. 当前角色

| Agent | 职责 | 默认沙箱 | 是否允许写生产代码 |
| --- | --- | --- | --- |
| `architect` | 需求、调用链、影响范围、事务/Redis/并发分析 | `read-only` | 否 |
| `implementer` | 按唯一方案实施最小修改 | `workspace-write` | 是，且同一时刻只能有一个 |
| `tester` | 分析测试缺口、补测试、运行验证 | `workspace-write` | 原则上否，只写测试 |
| `reviewer` | 正确性、安全、事务、并发和回归审查 | `read-only` | 否 |

四个角色没有写死 `model`，会继承父会话当前可用模型，避免项目配置因模型下线、账户权限或客户端差异失效。角色分别设置了适合其职责的 `model_reasoning_effort`。

## 4. 并发设置

`.codex/config.toml` 当前设置：

```toml
[agents]
max_threads = 4
max_depth = 1
interrupt_message = true
job_max_runtime_seconds = 1800
```

- `max_threads = 4`：最多保留 4 个并发 Agent 线程，包含主线程时应控制实际同时工作的子 Agent 数量。
- `max_depth = 1`：只允许主 Agent 创建直接子 Agent，禁止子 Agent 继续递归派生。
- `interrupt_message = true`：中断时给 Agent 上下文保留可见说明。
- `job_max_runtime_seconds = 1800`：批量 CSV Agent 作业的默认单 Worker 上限为 30 分钟；普通 `spawn_agent` 不依赖该值。

当前 Codex 版本默认启用 Subagent，不需要额外添加实验性 feature 开关。

## 5. 首次启用

1. 拉取包含 `.codex/` 的最新 `dev` 分支。
2. 从外层仓库根目录打开 Codex 项目，并在客户端提示时信任该项目配置。
3. 关闭并重新打开 Codex 任务，确保新的 `AGENTS.md` 和自定义 Agent 被重新加载。
4. 在 CLI 中输入 `/agent`，或在桌面版/IDE 的 Agent 面板查看活动线程。
5. 先执行下面的只读验证提示词，确认 `architect` 可以被识别。

```text
请启动 architect，只读分析当前项目的排行榜模块。
不要修改任何文件。返回 Controller -> Service -> Mapper -> MySQL -> Redis 的真实调用链，
并列出已检查文件、证据、风险和建议下一步。
```

如果当前任务是在配置提交之前创建的，必须新建或重新打开任务；旧任务不一定会热加载新增配置。

## 6. 标准开发提示词

```text
请使用项目配置的多 Agent 协作完成“<功能名称>”。

1. 让 architect 只读分析需求、真实调用链、影响文件、数据库、Redis、事务、权限和测试范围。
2. 如果涉及事务、并发或安全，同时让 reviewer 只读扫描风险。
3. 等待分析完成，由主 Agent 汇总唯一实施方案、文件所有权和停止条件。
4. 只让一个 implementer 按方案修改生产代码；任何时刻不得让两个 Agent 修改同一文件。
5. 实现完成后，让 tester 补充并运行测试，同时让 reviewer 只读审查最终差异。
6. 主 Agent 处理阻塞性问题，运行最终相关测试，并按 AGENTS.md 提交、推送和总结。

所有 Agent 必须保留现有未提交修改，不新增依赖，不擅自修改数据库结构或扩大模块范围。
```

分析完成前不要让 `implementer` 写代码。若任务会触发新增依赖、数据库变更、跨模块修改或安全边界不明确，按项目规则暂停并报告。

## 7. 并行只读审查提示词

```text
请并行使用只读 Agent 审查当前分支相对 main 的改动：

- reviewer A：事务、数据库更新行数、唯一约束和并发；
- reviewer B：Redis Key、TTL、缓存降级和 MySQL 一致性；
- reviewer C：JWT、角色权限、资源归属、路径和输入安全；
- tester：只读分析测试缺口，不修改文件。

等待全部完成后，由主 Agent 按 P0、P1、P2 汇总。
每项发现必须包含文件、方法、触发条件、影响、证据和最小修复建议。
```

## 8. 文件所有权与 Git

- Controller、Service、Mapper、Mapper XML、SQL 和强关联文档优先交给同一个 `implementer`，避免跨文件半成品。
- `tester` 原则上只修改 `src/test` 或前端测试文件；生产缺陷返回主 Agent 处理。
- `architect`、`reviewer` 不得写文件。
- Subagent 看到的是同一工作区，包括用户尚未提交的修改；委派前后都要运行 `git status`。
- 不使用无选择的 `git add .`。主 Agent 必须显式暂存本任务文件，避免混入其他改动。
- 子 Agent 默认不执行 `git commit` 或 `git push`；由主 Agent 在测试通过后按根 `AGENTS.md` 完成提交和推送。

## 9. Subagent 与 Worktree 的边界

| 场景 | 推荐方式 |
| --- | --- |
| 一个完整模块内部的分析、实现、测试和审查 | 同一任务内使用 Subagent |
| 多个 Agent 只读审查同一差异 | 并行 Subagent |
| Controller、Service、Mapper 等强关联链路修改 | 单一 `implementer` |
| 两个相互独立功能需要同时写代码 | 两个独立 Git Worktree |

Subagent 不是独立分支。只有 Worktree 才能为并行写入提供独立检出目录；即使使用 Worktree，也要保证功能和文件边界清晰。

## 10. 权限注意事项

自定义 Agent 的 `sandbox_mode` 是其默认值。父会话在界面、`/permissions` 或启动参数中选择的实时权限会传递给子 Agent，并可能覆盖 Agent 文件中的默认沙箱设置。

因此：

- 启动分析和审查前，把父会话权限设置为满足任务的最小范围；
- 在委派文本中再次明确“只读、禁止修改”；
- 主 Agent 通过 `git diff` 和 `git status` 复核实际写入；
- 不把 `sandbox_mode = "read-only"` 当作唯一安全控制。

## 11. 验证与排查

### 11.1 配置语法检查

仓库不额外引入 TOML 依赖。可以使用本机 Python 3.11+ 标准库检查：

```powershell
@'
from pathlib import Path
import tomllib

for path in Path(".codex").rglob("*.toml"):
    with path.open("rb") as file:
        tomllib.load(file)
    print(f"OK {path}")
'@ | python -
```

### 11.2 找不到自定义 Agent

1. 确认文件位于外层仓库的 `.codex/agents/`。
2. 确认每个文件都包含 `name`、`description`、`developer_instructions`。
3. 确认 Codex 从该仓库根目录启动，项目配置已被信任。
4. 新开任务或重启客户端，再使用 `/agent` 或只读验证提示词检查。
5. 检查当前 Codex 客户端是否为支持项目级自定义 Agent 的较新版本。

### 11.3 Agent 没有按角色工作

- 提示词中直接点名角色、读写范围、等待条件和返回格式。
- 检查父会话权限是否覆盖了角色的默认沙箱。
- 检查更靠近当前目录的 `AGENTS.md` 是否有更具体且冲突的规则。
- 把过大的任务拆成独立、可验证的小任务；不要一次让多个写入 Agent 处理同一调用链。

### 11.4 并发或令牌消耗过高

- 降低 `max_threads`，优先保留并行只读分析。
- 保持 `max_depth = 1`，不要开启递归委派。
- 简单任务直接由主 Agent 完成，不强制使用所有角色。
- 及时结束不再需要的 Agent 线程。

## 12. 项目特殊规则

- 前端代码只有在用户明确要求“按前端任务队列自动推进”时才使用自动队列规则。
- 多 Agent 不能绕过前端停止条件；一旦需要后端、数据库、Redis、依赖或其他模块变更，必须暂停报告。
- 本项目默认在 `dev` 迭代；`main` 只保存稳定版本。
- 每个可独立验证的小功能测试通过后，由主 Agent 显式暂存、提交并推送当前开发分支。

## 13. 官方参考

- [Subagents：可用性、自定义 Agent、全局设置和示例](https://developers.openai.com/codex/multi-agent)
- [Codex 配置参考](https://developers.openai.com/codex/config-reference)
- [Codex 高级配置](https://developers.openai.com/codex/config-advanced)
- [Git Worktrees](https://developers.openai.com/codex/app/worktrees)

官方文档说明当前 Agent 文件格式仍可能演进。升级 Codex 后如出现不兼容，应先对照上述官方页面，再修改仓库配置和本文档。
