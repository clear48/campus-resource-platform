/** 创建下载记录后返回的两步下载凭据。 */
export interface DownloadRecordResult {
  downloadRecordId: number
  resourceId: number
  fileId: number
  downloadUrl: string
  /** 仅用于本次文件流请求的一次性票据，使用后立即失效。 */
  downloadTicket: string
  expireSeconds: number
  counted: boolean
}

/** 文件流请求成功后供页面保存文件的内容和响应头信息。 */
export interface DownloadFileResult {
  blob: Blob
  fileName: string
  contentType: string
}

/** “我的下载”列表 records 内由接口文档明确的字段。 */
export interface DownloadRecordItem {
  downloadRecordId: number
  resourceId: number
  title: string
  fileId: number
  downloadStatus: number
  createdAt: string
}

/** “我的下载”接口仅接受的分页参数。 */
export interface DownloadRecordListQuery {
  pageNo?: number
  pageSize?: number
}
