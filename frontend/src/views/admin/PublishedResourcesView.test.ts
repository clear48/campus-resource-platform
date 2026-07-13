import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getCategories } from '../../api/categories'
import { searchResources } from '../../api/search'
import { session } from '../../state/session'
import PublishedResourcesView from './PublishedResourcesView.vue'

vi.mock('../../api/categories', () => ({ getCategories: vi.fn() }))
vi.mock('../../api/search', () => ({ searchResources: vi.fn() }))

describe('PublishedResourcesView', () => {
  beforeEach(() => {
    session.setSession('admin-published-token', { userId: 90001, username: 'admin', nickname: '管理员', email: null, role: 2, status: 1 })
    vi.mocked(getCategories).mockResolvedValue([{ categoryId: 10, parentId: 0, categoryName: '计算机基础', description: null, sortOrder: 1 }])
    vi.mocked(searchResources).mockResolvedValue({
      records: [{ resourceId: 20001, title: '数据结构期末复习提纲', description: '重点知识', courseName: '数据结构', resourceType: 2, tags: ['复习'], downloadCount: 128, favoriteCount: 35, hotScore: 745, createdAt: '2026-07-02T10:00:00' }],
      pageNo: 1, pageSize: 10, total: 1, pages: 1,
    })
  })

  afterEach(() => session.clearSession())

  it('应使用公开搜索接口展示已发布资料，不渲染下架操作', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/admin/resources', name: 'admin-resources', component: PublishedResourcesView }, { path: '/resources/:resourceId', name: 'resource-detail', component: { template: '<div>详情</div>' } }, { path: '/login', name: 'login', component: { template: '<div>登录</div>' } }] })
    await router.push('/admin/resources')
    await router.isReady()
    const wrapper = mount(PublishedResourcesView, { global: { plugins: [ElementPlus, router] } })
    await flushPromises()

    expect(getCategories).toHaveBeenCalledWith({ parentId: 0 })
    expect(searchResources).toHaveBeenCalledWith({ sortBy: 'createdAt', order: 'desc', pageNo: 1, pageSize: 10, keyword: undefined, courseName: undefined })
    expect(wrapper.text()).toContain('数据结构期末复习提纲')
    expect(wrapper.text()).toContain('已通过')
    expect(wrapper.text()).not.toContain('下架资料')
  })
})
