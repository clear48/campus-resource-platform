import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { approveResource, getAuditRecords, getPendingReviews, rejectResource } from '../../api/admin/resources'
import { session } from '../../state/session'
import { ApiBusinessError } from '../../utils/request'
import ReviewManagementView from './ReviewManagementView.vue'

const { confirm } = vi.hoisted(() => ({ confirm: vi.fn() }))

vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus')
  return { ...actual, ElMessage: { success: vi.fn() }, ElMessageBox: { confirm } }
})
vi.mock('../../api/admin/resources', () => ({ approveResource: vi.fn(), getAuditRecords: vi.fn(), getPendingReviews: vi.fn(), rejectResource: vi.fn() }))

const dialogStub = {
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>',
}

describe('ReviewManagementView', () => {
  beforeEach(() => {
    session.setSession('admin-page-token', { userId: 90001, username: 'admin', nickname: '管理员', email: null, role: 2, status: 1 })
    vi.mocked(getPendingReviews).mockResolvedValue({
      records: [{ resourceId: 20001, title: '数据结构期末复习提纲', description: '重点内容', categoryId: 10, courseName: '数据结构', resourceType: 2, tags: ['复习'], fileId: 30001, uploaderId: 10001, status: 0, createdAt: '2026-07-02T10:00:00' }],
      pageNo: 1, pageSize: 10, total: 1, pages: 1,
    })
    vi.mocked(getAuditRecords).mockResolvedValue([{ auditRecordId: 50001, resourceId: 20001, auditorId: 90001, actionType: 1, beforeStatus: 0, afterStatus: 1, auditReason: '资料完整', createdAt: '2026-07-02T11:00:00' }])
    vi.mocked(approveResource).mockResolvedValue({ resourceId: 20001, actionType: 1, beforeStatus: 0, afterStatus: 1, auditRecordId: 50001, auditReason: null, approvedAt: '2026-07-02T11:00:00', offlineAt: null })
    vi.mocked(rejectResource).mockResolvedValue({ resourceId: 20001, actionType: 2, beforeStatus: 0, afterStatus: 2, auditRecordId: 50002, auditReason: '请补充实验截图', approvedAt: null, offlineAt: null })
    confirm.mockResolvedValue(undefined)
  })

  afterEach(() => session.clearSession())

  it('应显示待审核列表，并调用独立接口查看审核流水', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/admin/reviews', name: 'admin-reviews', component: ReviewManagementView }, { path: '/login', name: 'login', component: { template: '<div>登录</div>' } }] })
    await router.push('/admin/reviews')
    await router.isReady()
    const wrapper = mount(ReviewManagementView, { global: { plugins: [ElementPlus, router], stubs: { ElDialog: dialogStub } } })
    await flushPromises()

    expect(getPendingReviews).toHaveBeenCalledWith({ pageNo: 1, pageSize: 10 })
    expect(wrapper.text()).toContain('数据结构期末复习提纲')
    expect(wrapper.text()).not.toContain('审核通过')
    await wrapper.get('[data-test="audit-records-20001"]').trigger('click')
    await flushPromises()
    expect(getAuditRecords).toHaveBeenCalledWith(20001)
  })

  it('应二次确认后审核通过，并在后端返回冲突时展示原始错误', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/admin/reviews', name: 'admin-reviews', component: ReviewManagementView }, { path: '/login', name: 'login', component: { template: '<div>登录</div>' } }] })
    await router.push('/admin/reviews')
    await router.isReady()
    const wrapper = mount(ReviewManagementView, { global: { plugins: [ElementPlus, router], stubs: { ElDialog: dialogStub } } })
    await flushPromises()
    await wrapper.get('[data-test="approve-20001"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalled()
    expect(approveResource).toHaveBeenCalledWith(20001, {})

    vi.mocked(approveResource).mockRejectedValueOnce(new ApiBusinessError(40901, '当前状态不是待审核'))
    await wrapper.get('[data-test="approve-20001"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('当前状态不是待审核')
  })

  it('应拒绝空原因，并在填写原因后二次确认提交', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/admin/reviews', name: 'admin-reviews', component: ReviewManagementView }, { path: '/login', name: 'login', component: { template: '<div>登录</div>' } }] })
    await router.push('/admin/reviews')
    await router.isReady()
    const wrapper = mount(ReviewManagementView, { global: { plugins: [ElementPlus, router], stubs: { ElDialog: dialogStub } } })
    await flushPromises()
    await wrapper.get('[data-test="reject-20001"]').trigger('click')
    await wrapper.get('[data-test="reject-submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('请填写拒绝原因')
    expect(rejectResource).not.toHaveBeenCalled()

    await wrapper.get('[data-test="reject-reason"]').setValue('请补充实验截图')
    await wrapper.get('[data-test="reject-submit"]').trigger('click')
    await flushPromises()
    expect(rejectResource).toHaveBeenCalledWith(20001, { rejectReason: '请补充实验截图' })
  })
})
