import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { checkFileDuplicate, uploadFile } from './files'
import { session } from '../state/session'
import { httpClient } from '../utils/request'

describe('files api', () => {
  let mock: MockAdapter

  beforeEach(() => {
    session.setSession('file-token', { userId: 10001, username: '20260001', nickname: '张三', email: null, role: 1, status: 1 })
    mock = new MockAdapter(httpClient)
  })

  afterEach(() => {
    mock.restore()
    session.clearSession()
  })

  it('应使用 MD5 和文件大小 query 预检秒传', async () => {
    mock.onGet('/files/check').reply((config) => {
      expect(config.params).toEqual({ fileMd5: '5d41402abc4b2a76b9719d911017c592', fileSize: 5 })
      expect(config.headers?.Authorization).toBe('Bearer file-token')
      return [200, { code: 0, message: 'success', data: { secondUpload: true, fileId: 30001 }, traceId: 'file-check-trace' }]
    })

    await expect(checkFileDuplicate({ fileMd5: '5d41402abc4b2a76b9719d911017c592', fileSize: 5 })).resolves.toMatchObject({ secondUpload: true, fileId: 30001 })
  })

  it('应以 FormData 上传文件且保留进度回调', async () => {
    const file = new File(['hello'], 'hello.txt', { type: 'text/plain' })
    mock.onPost('/files').reply((config) => {
      expect(config.headers?.Authorization).toBe('Bearer file-token')
      expect((config.data as FormData).get('file')).toBe(file)
      return [200, { code: 0, message: 'success', data: { fileId: 30001, fileMd5: '5d41402abc4b2a76b9719d911017c592', originalName: 'hello.txt', fileSize: 5, fileExt: 'txt', secondUpload: false }, traceId: 'file-upload-trace' }]
    })

    await expect(uploadFile(file)).resolves.toMatchObject({ fileId: 30001, secondUpload: false })
  })
})
