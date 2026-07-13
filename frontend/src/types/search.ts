/** 后端搜索接口允许的排序字段，避免页面直接拼接任意字段。 */
export type SearchResourceSortBy = 'createdAt' | 'downloadCount' | 'favoriteCount' | 'hotScore'

export type SearchOrder = 'asc' | 'desc'

/** 公开资料搜索的全部 query 参数，后端固定只返回 APPROVED 资料。 */
export interface SearchResourceQuery {
  keyword?: string
  categoryId?: number
  courseName?: string
  resourceType?: number
  tag?: string
  sortBy?: SearchResourceSortBy
  order?: SearchOrder
  pageNo?: number
  pageSize?: number
}

/** 搜索结果 records 内由后端文档明确给出的字段。 */
export interface SearchResourceItem {
  resourceId: number
  title: string
  description: string | null
  courseName: string
  resourceType: number
  tags: string[]
  downloadCount: number
  favoriteCount: number
  hotScore: number
  createdAt: string
}
