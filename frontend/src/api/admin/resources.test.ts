import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { approveResource, getAuditRecords, getPendingReviews, rejectResource } from './resources'
import { session } from '../../state/session'
import { httpClient } from '../../utils/request'

describe('admin resources api', () => {
  let mock: MockAdapter

  beforeEach(() => {
    session.setSession('admin-review-token', {
      userId: 90001,
      username: 'admin',
      nickname: '管理员',
      email: null,
      role: 2,
      status: 1,
    })
    mock = new MockAdapter(httpClient)
  })

  afterEach(() => {
    mock.restore()
    session.clearSession()
  })

  it('应携带 Token 和 query 查询待审核资料', async () => {
    const params = { courseName: '数据结构', resourceType: 2, uploaderId: 10001, pageNo: 1, pageSize: 10 }

    mock.onGet('/admin/resources/pending-reviews').reply((config) => {
      expect(config.headers?.Authorization).toBe('Bearer admin-review-token')
      expect(config.params).toEqual(params)

      return [200, {
        code: 0,
        message: 'success',
        data: {
          records: [{
            resourceId: 20001,
            title: '数据结构期末复习提纲',
            description: '覆盖排序、树、图等重点内容',
            categoryId: 10,
            courseName: '数据结构',
            resourceType: 2,
            tags: ['数据结构', '复习'],
            fileId: 30001,
            uploaderId: 10001,
            status: 0,
            createdAt: '2026-07-02T10:00:00',
          }],
          pageNo: 1,
          pageSize: 10,
          total: 1,
          pages: 1,
        },
        traceId: 'pending-review-trace',
      }]
    })

    await expect(getPendingReviews(params)).resolves.toMatchObject({
      total: 1,
      records: [{ resourceId: 20001, uploaderId: 10001 }],
    })
  })

  it('应携带 Token 查询资料审核流水', async () => {
    mock.onGet('/admin/resources/20001/audit-records').reply((config) => {
      expect(config.headers?.Authorization).toBe('Bearer admin-review-token')

      return [200, {
        code: 0,
        message: 'success',
        data: [{
          auditRecordId: 50001,
          resourceId: 20001,
          auditorId: 90001,
          actionType: 1,
          beforeStatus: 0,
          afterStatus: 1,
          auditReason: '资料内容完整，允许发布',
          createdAt: '2026-07-02T11:00:00',
        }],
        traceId: 'audit-record-trace',
      }]
    })

    await expect(getAuditRecords(20001)).resolves.toMatchObject([
      { auditRecordId: 50001, actionType: 1 },
    ])
  })

  it('应携带 Token 和可选意见审核通过资料', async () => {
    mock.onPost('/admin/resources/20001/audit-approvals').reply((config) => {
      expect(config.headers?.Authorization).toBe('Bearer admin-review-token')
      expect(JSON.parse(config.data)).toEqual({ auditReason: '资料内容完整，允许发布' })

      return [200, {
        code: 0,
        message: 'success',
        data: { resourceId: 20001, actionType: 1, beforeStatus: 0, afterStatus: 1, auditRecordId: 50001, auditReason: '资料内容完整，允许发布', approvedAt: '2026-07-02T11:00:00', offlineAt: null },
        traceId: 'approve-trace',
      }]
    })

    await expect(approveResource(20001, { auditReason: '资料内容完整，允许发布' })).resolves.toMatchObject({ afterStatus: 1 })
  })

  it('应携带 Token 和拒绝原因审核拒绝资料', async () => {
    mock.onPost('/admin/resources/20001/audit-rejections').reply((config) => {
      expect(config.headers?.Authorization).toBe('Bearer admin-review-token')
      expect(JSON.parse(config.data)).toEqual({ rejectReason: '请补充实验截图' })

      return [200, {
        code: 0,
        message: 'success',
        data: { resourceId: 20001, actionType: 2, beforeStatus: 0, afterStatus: 2, auditRecordId: 50002, auditReason: '请补充实验截图', approvedAt: null, offlineAt: null },
        traceId: 'reject-trace',
      }]
    })

    await expect(rejectResource(20001, { rejectReason: '请补充实验截图' })).resolves.toMatchObject({ afterStatus: 2 })
  })
})
