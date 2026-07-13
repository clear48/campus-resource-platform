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
  })
})
