import { describe, expect, it } from 'vitest'
import {
  getResourceStatusLabel,
  getResourceTypeLabel,
  getUserRoleLabel,
} from '../types/enums'
import { formatDateTime, formatNumber } from './format'

describe('展示枚举与格式化工具', () => {
  it('应将已知后端枚举转换为中文展示文案', () => {
    expect(getUserRoleLabel(2)).toBe('管理员')
    expect(getResourceStatusLabel(1)).toBe('已通过')
    expect(getResourceTypeLabel(99)).toBe('其他')
  })

  it('应为未知枚举提供稳定兜底', () => {
    expect(getUserRoleLabel(999)).toBe('未知')
    expect(getResourceStatusLabel(undefined)).toBe('未知')
  })

  it('应格式化有效时间和统计数字', () => {
    expect(formatDateTime('2026-07-13T09:05:01')).toBe('2026-07-13 09:05:01')
    expect(formatNumber(1234567)).toBe('1,234,567')
  })

  it('应为无效数据返回占位文本', () => {
    expect(formatDateTime('not-a-date')).toBe('--')
    expect(formatNumber(null)).toBe('--')
  })
})
