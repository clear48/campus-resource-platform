import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RegisterView from './RegisterView.vue'

const { register } = vi.hoisted(() => ({ register: vi.fn() }))

vi.mock('../api/auth', () => ({ register }))

beforeEach(() => register.mockReset())

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

  it('注册成功时应忽略空白可选字段并跳转登录页', async () => {
    register.mockResolvedValue({ userId: 10002, username: 'new-student', nickname: '新同学', email: null, role: 1, status: 1 })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/login', component: { template: '<div>登录</div>' } },
        { path: '/register', component: RegisterView },
      ],
    })
    await router.push('/register')
    await router.isReady()
    const wrapper = mount(RegisterView, { global: { plugins: [ElementPlus, router] } })

    await wrapper.get('input[placeholder="建议使用学号"]').setValue('new-student')
    await wrapper.get('input[placeholder="8 到 50 个字符"]').setValue('password123')
    await wrapper.get('input[placeholder="请输入昵称"]').setValue('新同学')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(register).toHaveBeenCalledWith({
      username: 'new-student',
      password: 'password123',
      nickname: '新同学',
      email: undefined,
      phone: undefined,
    })
    expect(router.currentRoute.value.path).toBe('/login')
  })
})
