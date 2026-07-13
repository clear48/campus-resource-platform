import type { PageResult } from '../../types/api'
import type { AuditRecordItem, PendingReviewQuery, PendingReviewResource } from '../../types/audit'
import { request } from '../../utils/request'

/** 查询管理员待审核队列；管理员权限仍由后端作为最终边界。 */
export function getPendingReviews(params: PendingReviewQuery): Promise<PageResult<PendingReviewResource>> {
  return request.get<PageResult<PendingReviewResource>>('/admin/resources/pending-reviews', { params })
}

/** 查询指定资料的审核流水；本任务只读，不包含任何状态流转操作。 */
export function getAuditRecords(resourceId: number): Promise<AuditRecordItem[]> {
  return request.get<AuditRecordItem[]>(`/admin/resources/${resourceId}/audit-records`)
}
