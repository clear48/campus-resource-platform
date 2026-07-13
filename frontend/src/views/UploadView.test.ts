import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import UploadView from './UploadView.vue'

const { calculateFileMd5 } = vi.hoisted(() => ({ calculateFileMd5: vi.fn() }))
const { checkFileDuplicate, uploadFile } = vi.hoisted(() => ({ checkFileDuplicate: vi.fn(), uploadFile: vi.fn() }))
const { getCategories } = vi.hoisted(() => ({ getCategories: vi.fn() }))
const { createResource } = vi.hoisted(() => ({ createResource: vi.fn() }))

vi.mock('../utils/file-md5', () => ({ calculateFileMd5 }))
vi.mock('../api/files', () => ({ checkFileDuplicate, uploadFile }))
vi.mock('../api/categories', () => ({ getCategories }))
vi.mock('../api/resources', () => ({ createResource }))

// 简化 Select 的 DOM 交互，专注验证页面向创建资料接口传递的业务数据。
const selectStub = {
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template: '<select v-bind="$attrs" :value="modelValue" @change="$emit(\'update:modelValue\', Number($event.target.value))"><slot /></select>',
}
const optionStub = {
  props: ['label', 'value'],
  template: '<option :value="value">{{ label }}</option>',
}

describe('UploadView', () => {
  beforeEach(() => {
    calculateFileMd5.mockImplementation(async (_file: File, progress: (percent: number) => void) => {
      progress(100)
      return 'file-md5'
    })
    checkFileDuplicate.mockResolvedValue({ secondUpload: true, fileId: 30001 })
    getCategories.mockResolvedValue([{ categoryId: 10, parentId: 0, categoryName: '计算机基础', description: null, sortOrder: 1 }])
    createResource.mockResolvedValue({ resourceId: 20001, fileId: 30001, status: 0, statusName: 'PENDING_REVIEW', message: '资料已创建，等待管理员审核' })
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

  it('没有 fileId 时应禁用资料提交入口', () => {
    const wrapper = mount(UploadView, { global: { plugins: [ElementPlus] } })

    expect((wrapper.get('[data-test="metadata-submit"]').element as HTMLButtonElement).disabled).toBe(true)
  })

  it('应提交资料字段，并显示待审核结果', async () => {
    const wrapper = mount(UploadView, {
      global: {
        plugins: [ElementPlus],
        stubs: { ElSelect: selectStub, ElOption: optionStub },
      },
    })
    const file = new File(['hello'], 'hello.txt', { type: 'text/plain' })
    const input = wrapper.get('[data-test="file-input"]')

    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
    await flushPromises()
    await wrapper.get('[data-test="metadata-title"]').setValue('数据结构笔记')
    await wrapper.get('[data-test="metadata-description"]').setValue('重点复习内容')
    await wrapper.get('[data-test="metadata-course"]').setValue('数据结构')
    await wrapper.get('[data-test="metadata-tags"]').setValue('复习, 期末')
    const selects = wrapper.findAllComponents(selectStub)
    await selects[0].vm.$emit('update:modelValue', 10)
    await selects[1].vm.$emit('update:modelValue', 2)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(createResource).toHaveBeenCalledWith({
      fileId: 30001,
      title: '数据结构笔记',
      description: '重点复习内容',
      categoryId: 10,
      courseName: '数据结构',
      resourceType: 2,
      tags: ['复习', '期末'],
    })
    expect(wrapper.text()).toContain('资料 ID：20001')
    expect(wrapper.text()).toContain('待审核')
    expect(wrapper.get('[data-test="my-uploads-link"]').attributes('href')).toBe('/me/uploads')
  })
})
