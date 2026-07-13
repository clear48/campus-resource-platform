import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { getHotResources, getHotSearchKeywords } from './rankings'
import { httpClient } from '../utils/request'

describe('rankings api', () => {
  let mock: MockAdapter

  beforeEach(() => {
    mock = new MockAdapter(httpClient)
  })

  afterEach(() => {
    mock.restore()
  })

  it('应使用 query 查询热门资料榜', async () => {
    mock.onGet('/rankings/resources/hot').reply((config) => {
      expect(config.params).toEqual({ limit: 10, period: 'weekly' })

      return [200, {
        code: 0,
        message: 'success',
        data: [{
          rank: 1,
          resourceId: 20001,
          title: '数据结构复习提纲',
          courseName: '数据结构',
          downloadCount: 128,
          favoriteCount: 35,
          hotScore: 745,
        }],
        traceId: 'rank-resource-trace',
      }]
    })

    await expect(getHotResources({ limit: 10, period: 'weekly' })).resolves.toHaveLength(1)
  })

  it('应限制热门搜索词周期为日周月类型', async () => {
    mock.onGet('/rankings/search-keywords/hot').reply((config) => {
      expect(config.params).toEqual({ limit: 10, period: 'daily' })

      return [200, {
        code: 0,
        message: 'success',
        data: [{ rank: 1, keyword: '数据结构', searchCount: 256 }],
        traceId: 'rank-keyword-trace',
      }]
    })

    await expect(getHotSearchKeywords({ limit: 10, period: 'daily' })).resolves.toMatchObject([
      { keyword: '数据结构' },
    ])
  })
})
