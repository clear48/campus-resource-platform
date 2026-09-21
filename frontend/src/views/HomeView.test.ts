import ElementPlus from 'element-plus'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
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

const RouterLinkStub = defineComponent({
  name: 'RouterLink',
  props: ['to'],
  template: '<a><slot /></a>',
})

async function mountHome() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: HomeView },
      { path: '/search', component: { template: '<div>搜索页</div>' } },
      { path: '/upload', component: { template: '<div>上传页</div>' } },
    ],
  })
  await router.push('/')
  await router.isReady()

  const wrapper = mount(HomeView, {
    global: {
      plugins: [ElementPlus, router],
      stubs: { RouterLink: RouterLinkStub },
    },
  })

  return { router, wrapper }
}

function findRouterLink(wrapper: VueWrapper, text: string) {
  return wrapper.findAllComponents(RouterLinkStub).find((link) => link.text() === text)
}

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
    const { wrapper } = await mountHome()
    await flushPromises()

    expect(getHotResources).toHaveBeenCalledWith({ limit: 10, period: 'all' })
    expect(getHotSearchKeywords).toHaveBeenCalledWith({ limit: 10, period: 'daily' })
    expect(wrapper.text()).toContain('数据结构复习提纲')
    expect(wrapper.text()).toContain('热门搜索词')
  })

  it('搜索按钮与表单提交应清理关键词并构造搜索页 query', async () => {
    const { router, wrapper } = await mountHome()
    await flushPromises()
    const pushSpy = vi.spyOn(router, 'push')
    const searchButton = wrapper.get('button.home-hero__search-action')
    const searchForm = wrapper.get('form[role="search"]')

    // happy-dom 不模拟 submit 按钮的浏览器默认动作，按钮语义和表单提交分别验证。
    expect(searchButton.attributes('type')).toBe('submit')
    await wrapper.get('input[placeholder="搜索课程、资料标题或关键词"]').setValue('  UML 建模  ')
    await searchForm.trigger('submit')
    await flushPromises()
    expect(pushSpy).toHaveBeenLastCalledWith({
      path: '/search',
      query: { keyword: 'UML 建模' },
    })

    await wrapper.get('input[placeholder="搜索课程、资料标题或关键词"]').setValue('  数据结构  ')
    await searchForm.trigger('submit')
    await flushPromises()
    expect(pushSpy).toHaveBeenLastCalledWith({
      path: '/search',
      query: { keyword: '数据结构' },
    })

    await wrapper.get('input[placeholder="搜索课程、资料标题或关键词"]').setValue('   ')
    await searchForm.trigger('submit')
    await flushPromises()
    expect(pushSpy).toHaveBeenLastCalledWith({ path: '/search', query: {} })
  })

  it('热门关键词应链接到对应搜索结果', async () => {
    const { wrapper } = await mountHome()
    await flushPromises()

    expect(findRouterLink(wrapper, '# 数据结构')?.props('to')).toEqual({
      path: '/search',
      query: { keyword: '数据结构' },
    })
  })

  it('切换榜单周期时应同步更新 aria-pressed 与查询周期', async () => {
    const { wrapper } = await mountHome()
    await flushPromises()
    const resourceButtons = wrapper.get('[aria-label="热门资料榜单周期"]').findAll('button')
    const keywordButtons = wrapper.get('[aria-label="热门搜索词榜单周期"]').findAll('button')
    const resourceDaily = resourceButtons.find((button) => button.text() === '日榜')
    const resourceAll = resourceButtons.find((button) => button.text() === '总榜')
    const keywordWeekly = keywordButtons.find((button) => button.text() === '周榜')
    const keywordDaily = keywordButtons.find((button) => button.text() === '日榜')

    expect(resourceAll?.attributes('aria-pressed')).toBe('true')
    expect(resourceDaily?.attributes('aria-pressed')).toBe('false')
    await resourceDaily?.trigger('click')
    await flushPromises()
    expect(resourceDaily?.attributes('aria-pressed')).toBe('true')
    expect(resourceAll?.attributes('aria-pressed')).toBe('false')
    expect(getHotResources).toHaveBeenLastCalledWith({ limit: 10, period: 'daily' })

    expect(keywordDaily?.attributes('aria-pressed')).toBe('true')
    expect(keywordWeekly?.attributes('aria-pressed')).toBe('false')
    await keywordWeekly?.trigger('click')
    await flushPromises()
    expect(keywordWeekly?.attributes('aria-pressed')).toBe('true')
    expect(keywordDaily?.attributes('aria-pressed')).toBe('false')
    expect(getHotSearchKeywords).toHaveBeenLastCalledWith({ limit: 10, period: 'weekly' })
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

    const { wrapper } = await mountHome()
    await flushPromises()

    expect(wrapper.text()).toContain('排行榜服务暂不可用')
    await wrapper.get('[data-test="retry-resource-ranking"]').trigger('click')
    await flushPromises()

    expect(getHotResources).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('重试后的热门资料')
  })

  it('热门关键词加载失败后应支持重新加载', async () => {
    getHotSearchKeywords
      .mockRejectedValueOnce(new Error('热词服务暂不可用'))
      .mockResolvedValueOnce([{ rank: 1, keyword: '操作系统', searchCount: 99 }])

    const { wrapper } = await mountHome()
    await flushPromises()

    expect(wrapper.text()).toContain('热词服务暂不可用')
    await wrapper.get('[data-test="retry-keyword-ranking"]').trigger('click')
    await flushPromises()

    expect(getHotSearchKeywords).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('操作系统')
  })
})
