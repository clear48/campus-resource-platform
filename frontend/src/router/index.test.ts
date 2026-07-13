import { createMemoryHistory } from 'vue-router'
import { afterEach, describe, expect, it } from 'vitest'
import { createAppRouter } from './index'
import { session } from '../state/session'

describe('route guards', () => {
  afterEach(() => session.clearSession())

  it('游客访问受保护页面应跳转登录并保留返回地址', async () => {
    const router = createAppRouter(createMemoryHistory())
    await router.push('/me/uploads')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/me/uploads')
  })

  it('普通用户访问管理员页面应返回首页', async () => {
    session.setSession('student-token', { userId: 10001, username: 'student', nickname: '学生', email: null, role: 1, status: 1 })
    const router = createAppRouter(createMemoryHistory())
    await router.push('/admin/reviews')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('home')
  })

  it('管理员可访问管理员页面，已登录用户不再进入登录页', async () => {
    session.setSession('admin-token', { userId: 90001, username: 'admin', nickname: '管理员', email: null, role: 2, status: 1 })
    const router = createAppRouter(createMemoryHistory())
    await router.push('/admin/reviews')
    await router.isReady()
    expect(router.currentRoute.value.name).toBe('admin-reviews')

    await router.push('/login')
    expect(router.currentRoute.value.name).toBe('home')
  })
})
