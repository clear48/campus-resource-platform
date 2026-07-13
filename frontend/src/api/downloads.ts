import axios from 'axios'
import type { DownloadFileResult, DownloadRecordResult } from '../types/download'
import { getDownloadFileName, parseDownloadJsonError } from '../utils/file-download'
import { httpClient, request } from '../utils/request'

/** 第一步仅创建下载记录，由后端执行限流、去重和下载量统计。 */
export function createDownloadRecord(resourceId: number): Promise<DownloadRecordResult> {
  return request.post<DownloadRecordResult>(`/resources/${resourceId}/download-records`)
}

/** 第二步按下载记录请求二进制流，不能使用普通 JSON 解包请求层。 */
export async function downloadFile(downloadRecordId: number): Promise<DownloadFileResult> {
  try {
    const response = await httpClient.get<Blob>(`/download-records/${downloadRecordId}/file`, { responseType: 'blob' })
    const contentDisposition = response.headers['content-disposition']
    const contentType = response.headers['content-type']

    return {
      blob: response.data,
      // Axios 响应头可为 AxiosHeaders 等联合类型，下载工具只接收字符串值。
      fileName: getDownloadFileName(typeof contentDisposition === 'string' ? contentDisposition : undefined),
      contentType: typeof contentType === 'string' ? contentType : 'application/octet-stream',
    }
  } catch (error) {
    if (axios.isAxiosError<Blob>(error) && error.response?.data instanceof Blob) {
      const businessError = await parseDownloadJsonError(error.response.data)

      if (businessError) {
        throw businessError
      }
    }

    throw error
  }
}
