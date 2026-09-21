import ElementPlus from 'element-plus'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LoginView from './LoginView.vue'

const { login } = vi.hoisted(() => ({ login: vi.fn() }))

vi.mock('../api/auth', () => ({ login }))

const user = { userId: 10001, username: 'student', nickname: '学生', email: null, role: 1, status: 1 }

async function mountLogin(redirect?: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div>首页</div>' } },
      { path: '/login', component: LoginView },
      { path: '/register', component: { template: '<div>注册</div>' } },
      { path: '/upload', component: { template: '<div>上传页</div>' } },
    ],
  })
  await router.push({ path: '/login', query: redirect === undefined ? {} : { redirect } })
  await router.isReady()
  const wrapper = mount(LoginView, { global: { plugins: [ElementPlus, router] } })

  return { router, wrapper }
}

async function submitValidLogin(wrapper: VueWrapper) {
  await wrapper.get('input[placeholder="请输入账号"]').setValue('student')
  await wrapper.get('input[placeholder="请输入密码"]').setValue('password123')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

beforeEach(() => {
  login.mockReset()
  localStorage.clear()
})

describe('LoginView', () => {
  it('应展示账号、密码和注册入口', async () => {
    const { wrapper } = await mountLogin()

    expect(wrapper.find('input[placeholder="请输入账号"]').exists()).toBe(true)
    expect(wrapper.find('input[placeholder="请输入密码"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/register"]').text()).toBe('去注册')
  })

  it('登录成功后应提交凭据、保存会话并返回首页', async () => {
    login.mockResolvedValue({ accessToken: 'student-token', tokenType: 'Bearer', expiresIn: 7200, user })
    const { router, wrapper } = await mountLogin()
    await submitValidLogin(wrapper)

    expect(login).toHaveBeenCalledWith({ username: 'student', password: 'password123' })
    expect(localStorage.getItem('crp.access-token')).toBe('student-token')
    expect(JSON.parse(localStorage.getItem('crp.current-user') ?? 'null')).toEqual(user)
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('合法站内 redirect 应在登录后返回原受保护页面', async () => {
    login.mockResolvedValue({ accessToken: 'student-token', tokenType: 'Bearer', expiresIn: 7200, user })
    const { router, wrapper } = await mountLogin('/upload')

    await submitValidLogin(wrapper)

    expect(router.currentRoute.value.path).toBe('/upload')
  })

  it.each(['//evil.example', '/\\evil.example', '/upload\n/evil'])('危险 redirect %j 应在登录后回退首页', async (redirect) => {
    login.mockResolvedValue({ accessToken: 'student-token', tokenType: 'Bearer', expiresIn: 7200, user })
    const { router, wrapper } = await mountLogin(redirect)

    await submitValidLogin(wrapper)

    expect(router.currentRoute.value.path).toBe('/')
  })
})
