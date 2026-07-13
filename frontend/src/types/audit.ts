/** 管理员待审核列表允许传递的筛选与分页参数。 */
export interface PendingReviewQuery {
  courseName?: string
  resourceType?: number
  uploaderId?: number
  pageNo?: number
  pageSize?: number
}

/** 待审核资料分页 records 中由接口文档明确的字段。 */
export interface PendingReviewResource {
  resourceId: number
  title: string
  description: string | null
  categoryId: number
  courseName: string
  resourceType: number
  tags: string[]
  fileId: number
  uploaderId: number
  status: number
  createdAt: string
}

/** 管理员读取审核流水时由接口返回的单条记录。 */
export interface AuditRecordItem {
  auditRecordId: number
  resourceId: number
  auditorId: number
  actionType: number
  beforeStatus: number
  afterStatus: number
  auditReason: string | null
  createdAt: string
}

/** 审核通过可选填写意见；空请求体仍由后端按接口约定处理。 */
export interface ApproveResourceRequest {
  auditReason?: string
}

/** 审核拒绝必须携带拒绝原因，页面将在提交前执行非空校验。 */
export interface RejectResourceRequest {
  rejectReason: string
}

/** 审核通过、拒绝和下架接口共用的状态流转结果字段。 */
export interface AuditActionResult {
  resourceId: number
  actionType: number
  beforeStatus: number
  afterStatus: number
  auditRecordId: number
  auditReason: string | null
  approvedAt: string | null
  offlineAt: string | null
}
