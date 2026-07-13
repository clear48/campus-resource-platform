import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getAuditRecords, offlineResource } from '../../api/admin/resources'
import { getCategories } from '../../api/categories'
import { searchResources } from '../../api/search'
import { session } from '../../state/session'
import PublishedResourcesView from './PublishedResourcesView.vue'

const { confirm } = vi.hoisted(() => ({ confirm: vi.fn() }))

vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus')
  return { ...actual, ElMessage: { success: vi.fn() }, ElMessageBox: { confirm } }
})
vi.mock('../../api/admin/resources', () => ({ getAuditRecords: vi.fn(), offlineResource: vi.fn() }))
vi.mock('../../api/categories', () => ({ getCategories: vi.fn() }))
vi.mock('../../api/search', () => ({ searchResources: vi.fn() }))

const dialogStub = {
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>',
}

describe('PublishedResourcesView', () => {
  beforeEach(() => {
    session.setSession('admin-published-token', { userId: 90001, username: 'admin', nickname: '管理员', email: null, role: 2, status: 1 })
    vi.mocked(getCategories).mockResolvedValue([{ categoryId: 10, parentId: 0, categoryName: '计算机基础', description: null, sortOrder: 1 }])
    vi.mocked(searchResources).mockResolvedValue({
      records: [{ resourceId: 20001, title: '数据结构期末复习提纲', description: '重点知识', courseName: '数据结构', resourceType: 2, tags: ['复习'], downloadCount: 128, favoriteCount: 35, hotScore: 745, createdAt: '2026-07-02T10:00:00' }],
      pageNo: 1, pageSize: 10, total: 1, pages: 1,
    })
    vi.mocked(getAuditRecords).mockResolvedValue([{ auditRecordId: 50003, resourceId: 20001, auditorId: 90001, actionType: 3, beforeStatus: 1, afterStatus: 3, auditReason: '版权风险', createdAt: '2026-07-02T12:00:00' }])
    vi.mocked(offlineResource).mockResolvedValue({ resourceId: 20001, actionType: 3, beforeStatus: 1, afterStatus: 3, auditRecordId: 50003, auditReason: '版权风险', approvedAt: null, offlineAt: '2026-07-02T12:00:00' })
    confirm.mockResolvedValue(undefined)
  })

  afterEach(() => session.clearSession())

  it('应使用公开搜索接口展示已发布资料，不渲染下架操作', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/admin/resources', name: 'admin-resources', component: PublishedResourcesView }, { path: '/resources/:resourceId', name: 'resource-detail', component: { template: '<div>详情</div>' } }, { path: '/login', name: 'login', component: { template: '<div>登录</div>' } }] })
    await router.push('/admin/resources')
    await router.isReady()
    const wrapper = mount(PublishedResourcesView, { global: { plugins: [ElementPlus, router], stubs: { ElDialog: dialogStub } } })
    await flushPromises()

    expect(getCategories).toHaveBeenCalledWith({ parentId: 0 })
    expect(searchResources).toHaveBeenCalledWith({ sortBy: 'createdAt', order: 'desc', pageNo: 1, pageSize: 10, keyword: undefined, courseName: undefined })
    expect(wrapper.text()).toContain('数据结构期末复习提纲')
    expect(wrapper.text()).toContain('已通过')
    await wrapper.get('[data-test="published-audit-records-20001"]').trigger('click')
    await flushPromises()
    expect(getAuditRecords).toHaveBeenCalledWith(20001)
  })

  it('应要求下架原因，确认后调用下架接口并刷新公开列表', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/admin/resources', name: 'admin-resources', component: PublishedResourcesView }, { path: '/resources/:resourceId', name: 'resource-detail', component: { template: '<div>详情</div>' } }, { path: '/login', name: 'login', component: { template: '<div>登录</div>' } }] })
    await router.push('/admin/resources')
    await router.isReady()
    const wrapper = mount(PublishedResourcesView, { global: { plugins: [ElementPlus, router], stubs: { ElDialog: dialogStub } } })
    await flushPromises()
    await wrapper.get('[data-test="offline-20001"]').trigger('click')
    await wrapper.get('[data-test="offline-submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('请填写下架原因')
    expect(offlineResource).not.toHaveBeenCalled()

    await wrapper.get('[data-test="offline-reason"]').setValue('版权风险')
    await wrapper.get('[data-test="offline-submit"]').trigger('click')
    await flushPromises()
    expect(confirm).toHaveBeenCalled()
    expect(offlineResource).toHaveBeenCalledWith(20001, { offlineReason: '版权风险' })
    expect(searchResources).toHaveBeenCalledTimes(2)
  })
})
