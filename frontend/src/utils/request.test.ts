import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { httpClient, request } from './request'

describe('request', () => {
  let mock: MockAdapter

  beforeEach(() => {
    // 直接挂载到同一个 Axios 实例，验证页面后续复用的真实请求编排。
    mock = new MockAdapter(httpClient)
  })

  afterEach(() => {
    mock.restore()
  })

  it('应解包后端成功响应中的 data', async () => {
    mock.onGet('/health').reply(200, {
      code: 0,
      message: 'success',
      data: { status: 'UP' },
      traceId: 'trace-success',
    })

    await expect(request.get<{ status: string }>('/health')).resolves.toEqual({ status: 'UP' })
  })

  it('应保留业务错误码和 traceId', async () => {
    mock.onGet('/invalid').reply(200, {
      code: 40001,
      message: '参数不合法',
      data: null,
      traceId: 'trace-business-error',
    })

    await expect(request.get('/invalid')).rejects.toMatchObject({
      code: 40001,
      message: '参数不合法',
      traceId: 'trace-business-error',
    })
  })

  it('应转换非 2xx 的后端 JSON 错误', async () => {
    mock.onGet('/protected').reply(401, {
      code: 40101,
      message: '未登录或 Token 无效',
      data: null,
      traceId: 'trace-http-error',
    })

    await expect(request.get('/protected')).rejects.toMatchObject({
      code: 40101,
      traceId: 'trace-http-error',
    })
  })
})
