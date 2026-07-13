import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import HomeView from './HomeView.vue'

const { getHotResources, getHotSearchKeywords } = vi.hoisted(() => ({
  getHotResources: vi.fn(),
  getHotSearchKeywords: vi.fn(),
}))

vi.mock('../api/rankings', () => ({
  getHotResources,
  getHotSearchKeywords,
}))

describe('HomeView', () => {
  beforeEach(() => {
    getHotResources.mockResolvedValue([
      {
        rank: 1,
        resourceId: 20001,
        title: '数据结构复习提纲',
        courseName: '数据结构',
        downloadCount: 128,
        favoriteCount: 35,
        hotScore: 745,
      },
    ])
    getHotSearchKeywords.mockResolvedValue([{ rank: 1, keyword: '数据结构', searchCount: 256 }])
  })

  it('应加载并展示两个公开榜单', async () => {
    const wrapper = mount(HomeView, {
      global: {
        plugins: [ElementPlus],
      },
    })
    await flushPromises()

    expect(getHotResources).toHaveBeenCalledWith({ limit: 10, period: 'all' })
    expect(getHotSearchKeywords).toHaveBeenCalledWith({ limit: 10, period: 'daily' })
    expect(wrapper.text()).toContain('数据结构复习提纲')
    expect(wrapper.text()).toContain('热门搜索词')
  })
})
