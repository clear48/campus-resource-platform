import ElementPlus from 'element-plus'
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import DefaultLayout from './DefaultLayout.vue'
import { session } from '../state/session'

const RouterLinkStub = {
  props: ['to'],
  template: '<a :href="to"><slot /></a>',
}

function mountLayout() {
  return mount(DefaultLayout, {
    global: {
      plugins: [ElementPlus],
      stubs: {
        RouterLink: RouterLinkStub,
        RouterView: true,
      },
    },
  })
}

function expectLink(wrapper: ReturnType<typeof mountLayout>, text: string, href: string) {
  const link = wrapper.findAll('a').find((item) => item.text() === text)

  expect(link, `应存在导航入口：${text}`).toBeDefined()
  expect(link?.attributes('href')).toBe(href)
}

describe('DefaultLayout', () => {
  afterEach(() => session.clearSession())

  it('游客只显示公开、登录和注册入口', () => {
    const wrapper = mountLayout()

    expect(wrapper.text()).toContain('资料搜索')
    expect(wrapper.text()).toContain('登录')
    expect(wrapper.text()).toContain('注册')
    expect(wrapper.text()).not.toContain('上传资料')
    expectLink(wrapper, '资料搜索', '/search')
    expectLink(wrapper, '登录', '/login')
    expectLink(wrapper, '注册', '/register')
  })

  it('普通用户显示个人功能，但不显示管理员入口', () => {
    session.setSession('student-token', { userId: 10001, username: 'student', nickname: '学生', email: null, role: 1, status: 1 })
    const wrapper = mountLayout()

    expect(wrapper.text()).toContain('上传资料')
    expect(wrapper.text()).toContain('我的下载')
    expect(wrapper.text()).not.toContain('管理员入口')
    expectLink(wrapper, '上传资料', '/upload')
    expectLink(wrapper, '我的上传', '/me/uploads')
    expectLink(wrapper, '我的收藏', '/me/favorites')
    expectLink(wrapper, '我的下载', '/me/downloads')
    expectLink(wrapper, '个人信息', '/me/profile')
  })

  it('管理员额外显示固定的管理员入口', () => {
    session.setSession('admin-token', { userId: 90001, username: 'admin', nickname: '管理员', email: null, role: 2, status: 1 })
    const wrapper = mountLayout()

    expect(wrapper.text()).toContain('管理员入口')
    expectLink(wrapper, '管理员入口', '/admin/reviews')
  })
})
