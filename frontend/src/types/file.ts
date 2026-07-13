export interface FileCheckQuery {
  fileMd5: string
  fileSize: number
}

/** 未命中秒传时 fileId 可能为 null 或字段缺省，按文档同时兼容。 */
export interface FileCheckResult {
  secondUpload: boolean
  fileId?: number | null
}

export interface FileUploadResult {
  fileId: number
  fileMd5: string
  originalName: string
  fileSize: number
  fileExt: string
  secondUpload: boolean
}

export type UploadProgressCallback = (percent: number) => void
