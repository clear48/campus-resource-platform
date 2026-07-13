import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createDownloadRecord, downloadFile } from './downloads'
import { session } from '../state/session'
import { httpClient } from '../utils/request'

describe('downloads api', () => {
  let mock: MockAdapter

  beforeEach(() => {
    session.setSession('download-token', { userId: 10001, username: '20260001', nickname: '张三', email: null, role: 1, status: 1 })
    mock = new MockAdapter(httpClient)
  })

  afterEach(() => {
    mock.restore()
    session.clearSession()
  })

  it('应携带 Token 创建下载记录', async () => {
    mock.onPost('/resources/20001/download-records').reply((config) => {
      expect(config.headers?.Authorization).toBe('Bearer download-token')
      return [200, { code: 0, message: 'success', data: { downloadRecordId: 60001, resourceId: 20001, fileId: 30001, downloadUrl: '/api/v1/download-records/60001/file', expireSeconds: null, counted: true }, traceId: 'download-create-trace' }]
    })

    await expect(createDownloadRecord(20001)).resolves.toMatchObject({ downloadRecordId: 60001, counted: true })
  })

  it('应携带 Token 获取二进制文件流和中文文件名', async () => {
    mock.onGet('/download-records/60001/file').reply((config) => {
      expect(config.headers?.Authorization).toBe('Bearer download-token')
      return [200, new Blob(['file-content'], { type: 'application/pdf' }), {
        'content-type': 'application/pdf',
        'content-disposition': "attachment; filename*=UTF-8''%E6%95%B0%E6%8D%AE%E7%BB%93%E6%9E%84.pdf",
      }]
    })

    await expect(downloadFile(60001)).resolves.toMatchObject({ fileName: '数据结构.pdf', contentType: 'application/pdf' })
  })
})
