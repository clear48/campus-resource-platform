import type { ApiResponse } from '../types/api'
import { ApiBusinessError } from './request'

/** 优先解析 RFC 5987 文件名，兼容后端对中文名称的 filename* 编码。 */
export function getDownloadFileName(contentDisposition?: string): string {
  if (!contentDisposition) {
    return 'download'
  }

  const encodedMatch = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i)

  if (encodedMatch) {
    try {
      return decodeURIComponent(encodedMatch[1])
    } catch {
      return encodedMatch[1]
    }
  }

  const plainMatch = contentDisposition.match(/filename="?([^";]+)"?/i)
  return plainMatch?.[1] ?? 'download'
}

/** 将文件流接口在非 2xx 时返回的 JSON Blob 转为统一业务错误。 */
export async function parseDownloadJsonError(blob: Blob): Promise<ApiBusinessError | null> {
  if (!blob.type.includes('application/json')) {
    return null
  }

  try {
    const body = JSON.parse(await blob.text()) as ApiResponse<unknown>
    return typeof body.code === 'number'
      ? new ApiBusinessError(body.code, body.message, body.traceId)
      : null
  } catch {
    return null
  }
}

/** 在浏览器中保存二进制文件；文件名完全来自后端响应头而不是本地路径。 */
export function saveDownloadBlob(blob: Blob, fileName: string): void {
  const objectUrl = URL.createObjectURL(blob)
  const anchor = document.createElement('a')

  anchor.href = objectUrl
  anchor.download = fileName
  anchor.click()
  URL.revokeObjectURL(objectUrl)
}
