import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createResource, getResourceDetail } from './resources'
import { httpClient } from '../utils/request'

describe('resources api', () => {
  let mock: MockAdapter

  beforeEach(() => {
    mock = new MockAdapter(httpClient)
  })

  afterEach(() => {
    mock.restore()
  })

  it('应按路径获取公开资料详情', async () => {
    mock.onGet('/resources/20001').reply(200, {
      code: 0,
      message: 'success',
      data: {
        resourceId: 20001,
        title: '数据结构期末复习提纲',
        description: '覆盖排序、树、图等重点内容',
        categoryId: 10,
        categoryName: '计算机基础',
        courseName: '数据结构',
        resourceType: 2,
        tags: ['数据结构', '复习'],
        status: 1,
        downloadCount: 128,
        favoriteCount: 35,
        hotScore: 745,
        createdAt: '2026-07-02T10:00:00',
        favorited: null,
      },
      traceId: 'resource-detail-trace',
    })

    await expect(getResourceDetail(20001)).resolves.toMatchObject({
      resourceId: 20001,
      categoryName: '计算机基础',
      favorited: null,
    })
  })

  it('应提交 JSON 请求体创建待审核资料', async () => {
    const data = {
      fileId: 30001,
      title: '数据结构期末复习提纲',
      description: '覆盖排序、树、图等重点内容',
      categoryId: 10,
      courseName: '数据结构',
      resourceType: 2,
      tags: ['数据结构', '复习'],
    }

    mock.onPost('/resources').reply((config) => {
      expect(JSON.parse(config.data)).toEqual(data)

      return [200, {
        code: 0,
        message: 'success',
        data: {
          resourceId: 20001,
          fileId: 30001,
          status: 0,
          statusName: 'PENDING_REVIEW',
          message: '资料已创建，等待管理员审核',
        },
        traceId: 'resource-create-trace',
      }]
    })

    await expect(createResource(data)).resolves.toMatchObject({
      resourceId: 20001,
      status: 0,
      statusName: 'PENDING_REVIEW',
    })
  })
})
