import { describe, expect, it } from 'vitest'
import { getDownloadFileName, parseDownloadJsonError } from './file-download'

describe('file download helpers', () => {
  it('应解析 RFC 5987 编码的中文文件名', () => {
    expect(getDownloadFileName("attachment; filename*=UTF-8''%E6%95%B0%E6%8D%AE%E7%BB%93%E6%9E%84.pdf"))
      .toBe('数据结构.pdf')
  })

  it('应识别文件流接口返回的 JSON 错误 Blob', async () => {
    const error = await parseDownloadJsonError(new Blob([
      JSON.stringify({ code: 42901, message: '下载过于频繁', data: null, traceId: 'download-limit-trace' }),
    ], { type: 'application/json' }))

    expect(error).toMatchObject({ code: 42901, traceId: 'download-limit-trace' })
  })
})
