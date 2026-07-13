import ElementPlus from 'element-plus'
import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import LoginView from './LoginView.vue'

describe('LoginView', () => {
  it('应展示账号、密码和注册入口', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div>首页</div>' } },
        { path: '/login', component: LoginView },
        { path: '/register', component: { template: '<div>注册</div>' } },
      ],
    })

    await router.push('/login')
    await router.isReady()

    const wrapper = mount(LoginView, {
      global: {
        plugins: [ElementPlus, router],
      },
    })

    expect(wrapper.find('input[placeholder="请输入账号"]').exists()).toBe(true)
    expect(wrapper.find('input[placeholder="请输入密码"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/register"]').text()).toBe('去注册')
  })
})
