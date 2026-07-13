/** 后端 JSON 接口统一返回结构。 */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  traceId: string
}

/** 后端分页接口在 data 内返回的分页元数据。 */
export interface PageResult<T> {
  records: T[]
  pageNo: number
  pageSize: number
  total: number
  pages: number
}
