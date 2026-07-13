import { describe, expect, it, vi } from 'vitest'
import { calculateFileMd5 } from './file-md5'

describe('file md5', () => {
  it('应对分片文件计算稳定的 MD5 并报告完成进度', async () => {
    const progress = vi.fn()
    const file = new File(['hello'], 'hello.txt', { type: 'text/plain' })

    await expect(calculateFileMd5(file, progress, 2)).resolves.toBe('5d41402abc4b2a76b9719d911017c592')
    expect(progress).toHaveBeenLastCalledWith(100)
    expect(progress).toHaveBeenCalledTimes(3)
  })
})
