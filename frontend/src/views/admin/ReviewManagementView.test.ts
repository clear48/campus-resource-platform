import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getAuditRecords, getPendingReviews } from '../../api/admin/resources'
import { session } from '../../state/session'
import ReviewManagementView from './ReviewManagementView.vue'

vi.mock('../../api/admin/resources', () => ({ getAuditRecords: vi.fn(), getPendingReviews: vi.fn() }))

describe('ReviewManagementView', () => {
  beforeEach(() => {
    session.setSession('admin-page-token', { userId: 90001, username: 'admin', nickname: '管理员', email: null, role: 2, status: 1 })
    vi.mocked(getPendingReviews).mockResolvedValue({
      records: [{ resourceId: 20001, title: '数据结构期末复习提纲', description: '重点内容', categoryId: 10, courseName: '数据结构', resourceType: 2, tags: ['复习'], fileId: 30001, uploaderId: 10001, status: 0, createdAt: '2026-07-02T10:00:00' }],
      pageNo: 1, pageSize: 10, total: 1, pages: 1,
    })
    vi.mocked(getAuditRecords).mockResolvedValue([{ auditRecordId: 50001, resourceId: 20001, auditorId: 90001, actionType: 1, beforeStatus: 0, afterStatus: 1, auditReason: '资料完整', createdAt: '2026-07-02T11:00:00' }])
  })

  afterEach(() => session.clearSession())

  it('应显示待审核列表，并调用独立接口查看审核流水', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/admin/reviews', name: 'admin-reviews', component: ReviewManagementView }, { path: '/login', name: 'login', component: { template: '<div>登录</div>' } }] })
    await router.push('/admin/reviews')
    await router.isReady()
    const wrapper = mount(ReviewManagementView, { global: { plugins: [ElementPlus, router] } })
    await flushPromises()

    expect(getPendingReviews).toHaveBeenCalledWith({ pageNo: 1, pageSize: 10 })
    expect(wrapper.text()).toContain('数据结构期末复习提纲')
    expect(wrapper.text()).not.toContain('审核通过')
    await wrapper.get('[data-test="audit-records-20001"]').trigger('click')
    await flushPromises()
    expect(getAuditRecords).toHaveBeenCalledWith(20001)
  })
})
