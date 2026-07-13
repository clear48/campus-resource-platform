import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { getCategories } from './categories'
import { httpClient } from '../utils/request'

describe('categories api', () => {
  let mock: MockAdapter

  beforeEach(() => {
    mock = new MockAdapter(httpClient)
  })

  afterEach(() => {
    mock.restore()
  })

  it('应使用 parentId query 查询公开启用分类', async () => {
    mock.onGet('/categories').reply((config) => {
      expect(config.params).toEqual({ parentId: 0 })

      return [200, {
        code: 0,
        message: 'success',
        data: [{
          categoryId: 10,
          parentId: 0,
          categoryName: '计算机基础',
          description: '计算机公共基础课程资料',
          sortOrder: 1,
        }],
        traceId: 'category-trace',
      }]
    })

    await expect(getCategories({ parentId: 0 })).resolves.toMatchObject([
      { categoryId: 10, categoryName: '计算机基础' },
    ])
  })
})
