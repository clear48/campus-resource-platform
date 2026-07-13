import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { rebuildHotResourceRanking } from './rankings'
import { session } from '../../state/session'
import { httpClient } from '../../utils/request'

describe('admin rankings api', () => {
  let mock: MockAdapter

  beforeEach(() => {
    session.setSession('admin-ranking-token', { userId: 90001, username: 'admin', nickname: '管理员', email: null, role: 2, status: 1 })
    mock = new MockAdapter(httpClient)
  })

  afterEach(() => {
    mock.restore()
    session.clearSession()
  })

  it('应携带 Token、无业务参数重建热门资料总榜，并接受 data=null', async () => {
    mock.onPost('/admin/rankings/resources/hot/rebuild').reply((config) => {
      expect(config.headers?.Authorization).toBe('Bearer admin-ranking-token')
      expect(config.data).toBeUndefined()

      return [200, { code: 0, message: 'success', data: null, traceId: 'ranking-rebuild-trace' }]
    })

    await expect(rebuildHotResourceRanking()).resolves.toBeNull()
  })
})
