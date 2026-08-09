// 多 Agent 并行审查工作流 —— 从 Codex 并行审查流程迁移
// 用法: 在审查阶段通过 Workflow 工具调用，对 git diff 做多维度并行审查
export const meta = {
  name: 'multi-agent-review',
  description: '对当前变更做多维度并行只读审查：事务/并发、Redis/缓存、安全/权限',
  phases: [
    { title: '获取差异', detail: 'git diff 获取待审查变更' },
    { title: '并行审查', detail: '事务+Redis+安全三个维度并行审查' },
    { title: '汇总', detail: '按 P0/P1/P2 汇总审查发现' },
  ],
}

phase('获取差异')
// 由调用方通过 args 传入 diff 内容，或在此处获取
const diff = args?.diff || '当前分支变更'

phase('并行审查')

const dimensions = [
  {
    key: 'transaction',
    label: 'review:事务与并发',
    prompt: `你是事务与并发审查专家。请审查以下变更：

审查重点：
1. @Transactional 是否受自调用、异常捕获、代理边界或耗时 IO 影响。
2. 数据库更新是否检查影响行数（update/delete 返回值）。
3. 唯一索引和锁是否覆盖并发语义。
4. 事务边界是否正确（不应在事务中执行耗时文件 IO）。

对每个问题，给出：
- 严重等级（P0/P1/P2）
- 文件与方法
- 触发条件
- 实际影响
- 证据（引用具体代码行）
- 最小修复建议

变更内容：
${diff}`,
  },
  {
    key: 'redis',
    label: 'review:Redis与缓存',
    prompt: `你是 Redis 与缓存一致性审查专家。请审查以下变更：

审查重点：
1. Redis Key 格式是否正确（crp: 前缀 + 业务域分段）。
2. TTL 设置是否合理。
3. ZSet/Hash/String 数据结构使用是否正确。
4. 缓存失效和降级策略是否完备。
5. Redis 与 MySQL 一致性处理是否正确。

对每个问题，给出：
- 严重等级（P0/P1/P2）
- 文件与方法
- 触发条件
- 实际影响
- 证据（引用具体代码行）
- 最小修复建议

变更内容：
${diff}`,
  },
  {
    key: 'security',
    label: 'review:安全与权限',
    prompt: `你是安全与权限审查专家。请审查以下变更：

审查重点：
1. 用户输入是否进入动态 SQL、文件名、路径或权限敏感操作。
2. JWT 校验和角色权限是否在正确层级执行。
3. 资源归属校验是否完备（用户只能操作自己的资源）。
4. 是否存在路径穿越、未授权访问或信息泄露风险。
5. 参数校验是否完备（@Valid、空值、范围）。

对每个问题，给出：
- 严重等级（P0/P1/P2）
- 文件与方法
- 触发条件
- 实际影响
- 证据（引用具体代码行）
- 最小修复建议

变更内容：
${diff}`,
  },
]

// 并行启动三个维度的审查
const reviews = await parallel(
  dimensions.map(d => () =>
    agent(d.prompt, {
      label: d.label,
      phase: '并行审查',
      agentType: 'reviewer',
    })
  )
)

phase('汇总')

// 合并结果
const allFindings = reviews
  .filter(Boolean)
  .map((text, i) => ({
    dimension: dimensions[i].key,
    report: text,
  }))

const summary = {
  dimensions: allFindings,
  reviewCount: allFindings.length,
  timestamp: new Date().toISOString(),
}

log(`审查完成：${allFindings.length} 个维度已返回结果`)

return summary
