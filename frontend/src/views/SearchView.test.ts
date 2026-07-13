import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SearchView from './SearchView.vue'

const { getCategories, searchResources } = vi.hoisted(() => ({
  getCategories: vi.fn(),
  searchResources: vi.fn(),
}))

vi.mock('../api/categories', () => ({ getCategories }))
vi.mock('../api/search', () => ({ searchResources }))

describe('SearchView', () => {
  beforeEach(() => {
    getCategories.mockResolvedValue([{ categoryId: 10, parentId: 0, categoryName: '计算机基础', description: null, sortOrder: 1 }])
    searchResources.mockResolvedValue({
      records: [{
        resourceId: 20001,
        title: '数据结构期末复习提纲',
        description: '重点知识',
        courseName: '数据结构',
        resourceType: 2,
        tags: ['复习'],
        downloadCount: 128,
        favoriteCount: 35,
        hotScore: 745,
        createdAt: '2026-07-02 10:00:00',
      }],
      pageNo: 1,
      pageSize: 10,
      total: 1,
      pages: 1,
    })
  })

  it('应从 URL 回填关键词并查询公开资料', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/search', name: 'search', component: SearchView }],
    })
    await router.push('/search?keyword=数据结构')
    await router.isReady()

    const wrapper = mount(SearchView, {
      global: {
        plugins: [ElementPlus, router],
      },
    })
    await flushPromises()

    expect(getCategories).toHaveBeenCalledWith({ parentId: 0 })
    expect(searchResources).toHaveBeenCalledWith({
      keyword: '数据结构',
      sortBy: 'createdAt',
      order: 'desc',
      pageNo: 1,
      pageSize: 10,
    })
    expect(wrapper.text()).toContain('数据结构期末复习提纲')
    expect(wrapper.text()).toContain('已审核通过资料')
  })
})
