import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { getCurrentUser, getMyResources } from './users'
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

  it('应携带 Token 并按状态和分页参数查询我的上传', async () => {
    session.setSession('resource-token', {
      userId: 10001,
      username: '20260001',
      nickname: '张三',
      email: null,
      role: 1,
      status: 1,
    })
    const params = { status: 0, pageNo: 1, pageSize: 10 }

    mock.onGet('/users/me/resources').reply((config) => {
      expect(config.headers?.Authorization).toBe('Bearer resource-token')
      expect(config.params).toEqual(params)

      return [200, {
        code: 0,
        message: 'success',
        data: {
          records: [{
            resourceId: 20002,
            title: '操作系统实验报告模板',
            courseName: '操作系统',
            status: 0,
            rejectReason: null,
            offlineReason: null,
            createdAt: '2026-07-02T10:30:00',
          }],
          pageNo: 1,
          pageSize: 10,
          total: 1,
          pages: 1,
        },
        traceId: 'my-resource-trace',
      }]
    })

    await expect(getMyResources(params)).resolves.toMatchObject({
      total: 1,
      records: [{ resourceId: 20002, title: '操作系统实验报告模板', status: 0 }],
    })
  })
})
