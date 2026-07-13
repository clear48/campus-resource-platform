import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { addFavorite, getFavoriteStatus, removeFavorite } from './favorites'
import { session } from '../state/session'
import { httpClient } from '../utils/request'

describe('favorites api', () => {
  let mock: MockAdapter

  beforeEach(() => {
    session.setSession('favorite-token', {
      userId: 10001,
      username: '20260001',
      nickname: '张三',
      email: null,
      role: 1,
      status: 1,
    })
    mock = new MockAdapter(httpClient)
  })

  afterEach(() => {
    mock.restore()
    session.clearSession()
  })

  it('应携带 Token 创建收藏关系', async () => {
    mock.onPost('/resources/20001/favorites').reply((config) => {
      expect(config.headers?.Authorization).toBe('Bearer favorite-token')

      return [200, {
        code: 0,
        message: 'success',
        data: { resourceId: 20001, favorited: true, duplicateIgnored: false, favoriteCount: 36, hotScoreDelta: 0 },
        traceId: 'favorite-add-trace',
      }]
    })

    await expect(addFavorite(20001)).resolves.toMatchObject({ favorited: true, favoriteCount: 36 })
  })

  it('应携带 Token 取消收藏关系', async () => {
    mock.onDelete('/resources/20001/favorites').reply((config) => {
      expect(config.headers?.Authorization).toBe('Bearer favorite-token')

      return [200, {
        code: 0,
        message: 'success',
        data: { resourceId: 20001, favorited: false, duplicateIgnored: false, favoriteCount: 35, hotScoreDelta: 0 },
        traceId: 'favorite-remove-trace',
      }]
    })

    await expect(removeFavorite(20001)).resolves.toMatchObject({ favorited: false, favoriteCount: 35 })
  })

  it('应携带 Token 查询当前收藏状态', async () => {
    mock.onGet('/resources/20001/favorite-status').reply((config) => {
      expect(config.headers?.Authorization).toBe('Bearer favorite-token')

      return [200, {
        code: 0,
        message: 'success',
        data: { resourceId: 20001, favorited: true },
        traceId: 'favorite-status-trace',
      }]
    })

    await expect(getFavoriteStatus(20001)).resolves.toEqual({ resourceId: 20001, favorited: true })
  })
})
