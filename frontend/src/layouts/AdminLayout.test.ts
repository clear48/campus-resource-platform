import ElementPlus from 'element-plus'
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AdminLayout from './AdminLayout.vue'

const RouterLinkStub = {
  props: ['to'],
  template: '<a :href="to"><slot /></a>',
}

describe('AdminLayout', () => {
  it('提供固定的管理端导航和返回前台入口', () => {
    const wrapper = mount(AdminLayout, {
      global: {
        plugins: [ElementPlus],
        stubs: {
          RouterLink: RouterLinkStub,
          RouterView: true,
        },
      },
    })

    expect(wrapper.text()).toContain('返回前台')
    expect(wrapper.text()).toContain('审核管理')
    expect(wrapper.text()).toContain('发布资料')
    expect(wrapper.text()).toContain('排行榜运维')

    // 图标文字与菜单标题位于同一链接内，因此按业务标题后缀匹配真实入口。
    const expectAdminLink = (text: string, href: string) => {
      const link = wrapper.findAll('a').find((item) => item.text().endsWith(text))
      expect(link, `应存在管理端入口：${text}`).toBeDefined()
      expect(link?.attributes('href')).toBe(href)
    }

    expectAdminLink('审核管理', '/admin/reviews')
    expectAdminLink('发布资料', '/admin/resources')
    expectAdminLink('排行榜运维', '/admin/rankings')
    expectAdminLink('返回前台', '/')
  })
})
