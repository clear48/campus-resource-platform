import type { PageResult } from '../../types/api'
import type {
  ApproveResourceRequest,
  AuditActionResult,
  AuditRecordItem,
  PendingReviewQuery,
  PendingReviewResource,
  RejectResourceRequest,
} from '../../types/audit'
import { request } from '../../utils/request'

/** 查询管理员待审核队列；管理员权限仍由后端作为最终边界。 */
export function getPendingReviews(params: PendingReviewQuery): Promise<PageResult<PendingReviewResource>> {
  return request.get<PageResult<PendingReviewResource>>('/admin/resources/pending-reviews', { params })
}

/** 查询指定资料的审核流水；本任务只读，不包含任何状态流转操作。 */
export function getAuditRecords(resourceId: number): Promise<AuditRecordItem[]> {
  return request.get<AuditRecordItem[]>(`/admin/resources/${resourceId}/audit-records`)
}

/** 审核通过待审核资料；后端负责状态机校验和审核流水事务写入。 */
export function approveResource(resourceId: number, data: ApproveResourceRequest): Promise<AuditActionResult> {
  return request.post<AuditActionResult, ApproveResourceRequest>(`/admin/resources/${resourceId}/audit-approvals`, data)
}

/** 审核拒绝待审核资料；调用方必须传入非空拒绝原因。 */
export function rejectResource(resourceId: number, data: RejectResourceRequest): Promise<AuditActionResult> {
  return request.post<AuditActionResult, RejectResourceRequest>(`/admin/resources/${resourceId}/audit-rejections`, data)
}
