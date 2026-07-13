import ElementPlus from 'element-plus'
import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import RegisterView from './RegisterView.vue'

describe('RegisterView', () => {
  it('应展示注册所需字段和登录入口', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/login', component: { template: '<div>登录</div>' } },
        { path: '/register', component: RegisterView },
      ],
    })

    await router.push('/register')
    await router.isReady()

    const wrapper = mount(RegisterView, {
      global: {
        plugins: [ElementPlus, router],
      },
    })

    expect(wrapper.find('input[placeholder="建议使用学号"]').exists()).toBe(true)
    expect(wrapper.find('input[placeholder="8 到 50 个字符"]').exists()).toBe(true)
    expect(wrapper.find('input[placeholder="请输入昵称"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/login"]').text()).toBe('去登录')
  })
})
