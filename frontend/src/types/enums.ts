/** 后端用户角色值对应的展示文案。 */
const userRoleLabels: Record<number, string> = {
  1: '学生',
  2: '管理员',
}

/** 后端 resource.status 对应的生命周期展示文案。 */
const resourceStatusLabels: Record<number, string> = {
  0: '待审核',
  1: '已通过',
  2: '已拒绝',
  3: '已下架',
  4: '已删除',
}

/** 后端 resourceType 对应的资料类型展示文案。 */
const resourceTypeLabels: Record<number, string> = {
  1: '课件',
  2: '笔记',
  3: '真题',
  4: '实验报告',
  5: '课程设计',
  99: '其他',
}

function getLabel(labels: Record<number, string>, value: number | null | undefined): string {
  if (value === null || value === undefined) {
    return '未知'
  }

  return labels[value] ?? '未知'
}

export function getUserRoleLabel(value: number | null | undefined): string {
  return getLabel(userRoleLabels, value)
}

export function getResourceStatusLabel(value: number | null | undefined): string {
  return getLabel(resourceStatusLabels, value)
}

export function getResourceTypeLabel(value: number | null | undefined): string {
  return getLabel(resourceTypeLabels, value)
}
