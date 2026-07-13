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
    getHotResources.mockReset()
    getHotSearchKeywords.mockReset()
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

  it('排行榜加载失败后应支持重新加载', async () => {
    getHotResources
      .mockRejectedValueOnce(new Error('排行榜服务暂不可用'))
      .mockResolvedValueOnce([
        {
          rank: 1,
          resourceId: 20002,
          title: '重试后的热门资料',
          courseName: '操作系统',
          downloadCount: 88,
          favoriteCount: 12,
          hotScore: 256,
        },
      ])

    const wrapper = mount(HomeView, {
      global: {
        plugins: [ElementPlus],
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('排行榜服务暂不可用')
    await wrapper.get('[data-test="retry-resource-ranking"]').trigger('click')
    await flushPromises()

    expect(getHotResources).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('重试后的热门资料')
  })
})
