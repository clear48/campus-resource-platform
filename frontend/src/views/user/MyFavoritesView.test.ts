import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { removeFavorite } from '../../api/favorites'
import { getMyFavorites } from '../../api/users'
import { session } from '../../state/session'
import MyFavoritesView from './MyFavoritesView.vue'

vi.mock('../../api/favorites', () => ({ removeFavorite: vi.fn() }))
vi.mock('../../api/users', () => ({ getMyFavorites: vi.fn() }))

describe('MyFavoritesView', () => {
  beforeEach(() => {
    session.setSession('favorite-view-token', {
      userId: 10001,
      username: '20260001',
      nickname: '张三',
      email: null,
      role: 1,
      status: 1,
    })
    vi.mocked(getMyFavorites).mockResolvedValue({
      records: [{
        resourceId: 20001,
        title: '数据结构期末复习提纲',
        courseName: '数据结构',
        downloadCount: 128,
        favoriteCount: 35,
        createdAt: '2026-07-02T10:00:00',
        favoriteAt: '2026-07-02T13:00:00',
      }],
      pageNo: 1,
      pageSize: 10,
      total: 1,
      pages: 1,
    })
    vi.mocked(removeFavorite).mockResolvedValue({ resourceId: 20001, favorited: false, duplicateIgnored: false, favoriteCount: 34, hotScoreDelta: 0 })
  })

  afterEach(() => {
    session.clearSession()
  })

  it('应展示收藏资料，并可取消收藏后重新读取列表', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/me/favorites', name: 'my-favorites', component: MyFavoritesView },
        { path: '/resources/:resourceId', name: 'resource-detail', component: { template: '<div>详情</div>' } },
        { path: '/login', name: 'login', component: { template: '<div>登录</div>' } },
      ],
    })
    await router.push('/me/favorites')
    await router.isReady()

    const wrapper = mount(MyFavoritesView, { global: { plugins: [ElementPlus, router] } })
    await flushPromises()

    expect(getMyFavorites).toHaveBeenCalledWith({ pageNo: 1, pageSize: 10 })
    expect(wrapper.text()).toContain('数据结构期末复习提纲')
    await wrapper.get('[data-test="remove-favorite-20001"]').trigger('click')
    await flushPromises()
    expect(removeFavorite).toHaveBeenCalledWith(20001)
    expect(getMyFavorites).toHaveBeenCalledTimes(2)
  })
})
