/** 公开资料详情接口已明确返回的字段；不包含文件存储路径和下载地址。 */
export interface ResourceDetail {
  resourceId: number
  title: string
  description: string | null
  categoryId: number
  categoryName: string
  courseName: string
  resourceType: number
  tags: string[]
  status: number
  downloadCount: number
  favoriteCount: number
  hotScore: number
  createdAt: string
  // 文档对该字段是否实时依据 Token 填充存在歧义，首版只按可空字段呈现。
  favorited: boolean | null
}

/** 已完成文件上传后，用 fileId 创建待审核资料的请求体。 */
export interface CreateResourceRequest {
  fileId: number
  title: string
  description?: string
  categoryId: number
  courseName: string
  resourceType: number
  tags?: string[]
}

/** 创建资料成功后由后端返回的待审核结果。 */
export interface CreateResourceResult {
  resourceId: number
  fileId: number
  status: number
  statusName: string
  message: string
}
