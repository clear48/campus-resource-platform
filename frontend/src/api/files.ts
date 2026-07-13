import type { FileCheckQuery, FileCheckResult, FileUploadResult, UploadProgressCallback } from '../types/file'
import { request } from '../utils/request'

/** 用前端分片计算的 MD5 和文件大小预检是否可秒传。 */
export function checkFileDuplicate(params: FileCheckQuery): Promise<FileCheckResult> {
  return request.get<FileCheckResult>('/files/check', { params })
}

/** 上传物理文件；资料元数据仍由后续的创建资料接口单独提交。 */
export function uploadFile(file: File, onProgress?: UploadProgressCallback): Promise<FileUploadResult> {
  const formData = new FormData()
  formData.append('file', file)

  return request.post<FileUploadResult, FormData>('/files', formData, {
    onUploadProgress: (event) => {
      if (event.total) {
        onProgress?.(Math.round((event.loaded / event.total) * 100))
      }
    },
  })
}
