import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { rebuildHotResourceRanking } from '../../api/admin/rankings'
import { getHotResources, getHotSearchKeywords } from '../../api/rankings'
import { session } from '../../state/session'
import RankingManagementView from './RankingManagementView.vue'

const { confirm } = vi.hoisted(() => ({ confirm: vi.fn() }))

vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus')
  return { ...actual, ElMessage: { success: vi.fn() }, ElMessageBox: { confirm } }
})
vi.mock('../../api/admin/rankings', () => ({ rebuildHotResourceRanking: vi.fn() }))
vi.mock('../../api/rankings', () => ({ getHotResources: vi.fn(), getHotSearchKeywords: vi.fn() }))

describe('RankingManagementView', () => {
  beforeEach(() => {
    session.setSession('admin-ranking-page-token', { userId: 90001, username: 'admin', nickname: '管理员', email: null, role: 2, status: 1 })
    vi.mocked(getHotResources).mockResolvedValue([{ rank: 1, resourceId: 20001, title: '数据结构复习提纲', courseName: '数据结构', downloadCount: 128, favoriteCount: 35, hotScore: 745 }])
    vi.mocked(getHotSearchKeywords).mockResolvedValue([{ rank: 1, keyword: '数据结构', searchCount: 256 }])
    vi.mocked(rebuildHotResourceRanking).mockResolvedValue(null)
    confirm.mockResolvedValue(undefined)
  })

  afterEach(() => session.clearSession())

  it('应展示两类榜单，并确认后重建 all 总榜而不展示虚构进度', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/admin/rankings', name: 'admin-rankings', component: RankingManagementView }, { path: '/login', name: 'login', component: { template: '<div>登录</div>' } }] })
    await router.push('/admin/rankings')
    await router.isReady()
    const wrapper = mount(RankingManagementView, { global: { plugins: [ElementPlus, router] } })
    await flushPromises()

    expect(getHotResources).toHaveBeenCalledWith({ period: 'all', limit: 10 })
    expect(getHotSearchKeywords).toHaveBeenCalledWith({ period: 'daily', limit: 10 })
    expect(wrapper.text()).toContain('数据结构复习提纲')
    expect(wrapper.text()).toContain('数据结构')
    await wrapper.get('[data-test="rebuild-ranking"]').trigger('click')
    await flushPromises()
    expect(confirm).toHaveBeenCalled()
    expect(rebuildHotResourceRanking).toHaveBeenCalled()
    expect(getHotResources).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain('重建进度')
  })
})
