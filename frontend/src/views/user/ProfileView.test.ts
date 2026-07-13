import ElementPlus from 'element-plus'
import MockAdapter from 'axios-mock-adapter'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { httpClient } from '../../utils/request'
import { session } from '../../state/session'
import ProfileView from './ProfileView.vue'

describe('ProfileView', () => {
  let mock: MockAdapter

  beforeEach(() => {
    session.setSession('profile-token', {
      userId: 10001,
      username: '20260001',
      nickname: '张三',
      email: 'zhangsan@example.com',
      role: 1,
      status: 1,
    })
    mock = new MockAdapter(httpClient)
  })

  afterEach(() => {
    mock.restore()
    session.clearSession()
  })

  it('应加载并展示当前用户字段', async () => {
    mock.onGet('/users/me').reply(200, {
      code: 0,
      message: 'success',
      data: {
        userId: 10001,
        username: '20260001',
        nickname: '张三',
        email: 'zhangsan@example.com',
        role: 1,
        status: 1,
      },
      traceId: 'profile-trace',
    })

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/login', component: { template: '<div>登录</div>' } },
        { path: '/me/profile', component: ProfileView },
      ],
    })
    await router.push('/me/profile')
    await router.isReady()

    const wrapper = mount(ProfileView, {
      global: {
        plugins: [ElementPlus, router],
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('20260001')
    expect(wrapper.text()).toContain('张三')
    expect(wrapper.text()).toContain('学生')
  })
})
