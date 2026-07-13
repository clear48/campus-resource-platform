import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { login, logout, register } from './auth'
import { httpClient } from '../utils/request'

describe('auth api', () => {
  let mock: MockAdapter

  beforeEach(() => {
    mock = new MockAdapter(httpClient)
  })

  afterEach(() => {
    mock.restore()
  })

  it('应将注册参数提交到认证注册接口', async () => {
    const registerPayload = {
      username: '20260001',
      password: 'Passw0rd123',
      nickname: '张三',
      email: 'zhangsan@example.com',
    }

    mock.onPost('/auth/register', registerPayload).reply(200, {
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
      traceId: 'register-trace',
    })

    await expect(register(registerPayload)).resolves.toMatchObject({ userId: 10001, role: 1 })
  })

  it('应返回登录 Token 和当前用户信息', async () => {
    mock.onPost('/auth/login').reply(200, {
      code: 0,
      message: 'success',
      data: {
        accessToken: 'jwt-token',
        tokenType: 'Bearer',
        expiresIn: 7200,
        user: {
          userId: 10001,
          username: '20260001',
          nickname: '张三',
          email: null,
          role: 1,
          status: 1,
        },
      },
      traceId: 'login-trace',
    })

    await expect(login({ username: '20260001', password: 'Passw0rd123' })).resolves.toMatchObject({
      accessToken: 'jwt-token',
      user: { username: '20260001' },
    })
  })

  it('应调用无业务请求体的退出接口', async () => {
    mock.onPost('/auth/logout').reply(200, {
      code: 0,
      message: 'success',
      data: null,
      traceId: 'logout-trace',
    })

    await expect(logout()).resolves.toBeNull()
  })
})
