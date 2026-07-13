import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { searchResources } from './search'
import { httpClient } from '../utils/request'

describe('search api', () => {
  let mock: MockAdapter

  beforeEach(() => {
    mock = new MockAdapter(httpClient)
  })

  afterEach(() => {
    mock.restore()
  })

  it('应使用 query 请求公开资料搜索并返回分页数据', async () => {
    const params = {
      keyword: '数据结构',
      categoryId: 10,
      sortBy: 'hotScore' as const,
      order: 'desc' as const,
      pageNo: 1,
      pageSize: 10,
    }

    mock.onGet('/search/resources').reply((config) => {
      expect(config.params).toEqual(params)

      return [200, {
        code: 0,
        message: 'success',
        data: {
          records: [{
            resourceId: 20001,
            title: '数据结构期末复习提纲',
            description: '覆盖排序、树、图等重点内容',
            courseName: '数据结构',
            resourceType: 2,
            tags: ['数据结构', '复习'],
            downloadCount: 128,
            favoriteCount: 35,
            hotScore: 745,
            createdAt: '2026-07-02 10:00:00',
          }],
          pageNo: 1,
          pageSize: 10,
          total: 1,
          pages: 1,
        },
        traceId: 'search-trace',
      }]
    })

    await expect(searchResources(params)).resolves.toMatchObject({
      total: 1,
      records: [{ resourceId: 20001, title: '数据结构期末复习提纲' }],
    })
  })
})
