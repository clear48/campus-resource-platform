import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { getCurrentUser } from './users'
import { session } from '../state/session'
import { httpClient } from '../utils/request'

describe('users api and session', () => {
  let mock: MockAdapter

  beforeEach(() => {
    session.clearSession()
    mock = new MockAdapter(httpClient)
  })

  afterEach(() => {
    mock.restore()
    session.clearSession()
  })

  it('应携带已恢复的 Bearer Token 查询当前用户', async () => {
    session.setSession('test-token', {
      userId: 10001,
      username: '20260001',
      nickname: '张三',
      email: null,
      role: 1,
      status: 1,
    })

    mock.onGet('/users/me').reply((config) => {
      expect(config.headers?.Authorization).toBe('Bearer test-token')

      return [200, {
        code: 0,
        message: 'success',
        data: {
          userId: 10001,
          username: '20260001',
          nickname: '张三',
          email: null,
          role: 1,
          status: 1,
        },
        traceId: 'user-trace',
      }]
    })

    await expect(getCurrentUser()).resolves.toMatchObject({ userId: 10001 })
  })

  it('应在 Token 无效时清理本地会话', async () => {
    session.setSession('expired-token', {
      userId: 10001,
      username: '20260001',
      nickname: '张三',
      email: null,
      role: 1,
      status: 1,
    })

    mock.onGet('/users/me').reply(401, {
      code: 40102,
      message: 'Token 已失效',
      data: null,
      traceId: 'expired-token-trace',
    })

    await expect(getCurrentUser()).rejects.toMatchObject({ code: 40102 })
    expect(session.isLoggedIn.value).toBe(false)
    expect(localStorage.getItem('crp.access-token')).toBeNull()
  })
})
