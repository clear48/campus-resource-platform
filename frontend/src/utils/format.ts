/** 页面无可展示数据时使用统一占位，避免各页面散落硬编码。 */
const emptyPlaceholder = '--'

/**
 * 将后端 ISO 时间转换为固定的本地阅读格式；非法时间不抛出异常，避免列表渲染被单条脏数据中断。
 */
export function formatDateTime(value: string | Date | null | undefined): string {
  if (!value) {
    return emptyPlaceholder
  }

  const date = value instanceof Date ? value : new Date(value)

  if (Number.isNaN(date.getTime())) {
    return emptyPlaceholder
  }

  const pad = (number: number) => String(number).padStart(2, '0')

  return [date.getFullYear(), pad(date.getMonth() + 1), pad(date.getDate())].join('-')
    + ` ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/** 将统计数值统一格式化，保留页面对 null/undefined 的稳定兜底。 */
export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return emptyPlaceholder
  }

  return new Intl.NumberFormat('zh-CN').format(value)
}
