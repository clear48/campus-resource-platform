import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ResourceDetailView from './ResourceDetailView.vue'

const { getResourceDetail } = vi.hoisted(() => ({ getResourceDetail: vi.fn() }))

vi.mock('../api/resources', () => ({ getResourceDetail }))

describe('ResourceDetailView', () => {
  beforeEach(() => {
    getResourceDetail.mockResolvedValue({
      resourceId: 20001,
      title: '数据结构期末复习提纲',
      description: '覆盖排序、树、图等重点内容',
      categoryId: 10,
      categoryName: '计算机基础',
      courseName: '数据结构',
      resourceType: 2,
      tags: ['数据结构', '复习'],
      status: 1,
      downloadCount: 128,
      favoriteCount: 35,
      hotScore: 745,
      createdAt: '2026-07-02T10:00:00',
      favorited: null,
    })
  })

  it('应允许游客按资料 ID 加载并展示只读详情', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/resources/:resourceId', name: 'resource-detail', component: ResourceDetailView }],
    })
    await router.push('/resources/20001')
    await router.isReady()

    const wrapper = mount(ResourceDetailView, {
      global: {
        plugins: [ElementPlus, router],
      },
    })
    await flushPromises()

    expect(getResourceDetail).toHaveBeenCalledWith(20001)
    expect(wrapper.text()).toContain('数据结构期末复习提纲')
    expect(wrapper.text()).toContain('计算机基础')
    expect(wrapper.text()).not.toContain('立即下载')
    expect(wrapper.text()).not.toContain('收藏资料')
  })
})
