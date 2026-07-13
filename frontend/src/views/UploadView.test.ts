import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import UploadView from './UploadView.vue'

const { calculateFileMd5 } = vi.hoisted(() => ({ calculateFileMd5: vi.fn() }))
const { checkFileDuplicate, uploadFile } = vi.hoisted(() => ({ checkFileDuplicate: vi.fn(), uploadFile: vi.fn() }))

vi.mock('../utils/file-md5', () => ({ calculateFileMd5 }))
vi.mock('../api/files', () => ({ checkFileDuplicate, uploadFile }))

describe('UploadView', () => {
  beforeEach(() => {
    calculateFileMd5.mockImplementation(async (_file: File, progress: (percent: number) => void) => {
      progress(100)
      return 'file-md5'
    })
    checkFileDuplicate.mockResolvedValue({ secondUpload: true, fileId: 30001 })
  })

  it('命中秒传时应复用 fileId 而不上传文件', async () => {
    const wrapper = mount(UploadView, { global: { plugins: [ElementPlus] } })
    const file = new File(['hello'], 'hello.txt', { type: 'text/plain' })
    const input = wrapper.get('[data-test="file-input"]')

    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
    await flushPromises()

    expect(checkFileDuplicate).toHaveBeenCalledWith({ fileMd5: 'file-md5', fileSize: 5 })
    expect(uploadFile).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('文件 ID：30001')
    expect(wrapper.text()).toContain('命中秒传')
  })
})
