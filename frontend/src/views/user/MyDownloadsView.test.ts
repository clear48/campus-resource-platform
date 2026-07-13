import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createDownloadRecord, downloadFile } from '../../api/downloads'
import { getMyDownloadRecords } from '../../api/users'
import { session } from '../../state/session'
import { saveDownloadBlob } from '../../utils/file-download'
import MyDownloadsView from './MyDownloadsView.vue'

vi.mock('../../api/downloads', () => ({ createDownloadRecord: vi.fn(), downloadFile: vi.fn() }))
vi.mock('../../api/users', () => ({ getMyDownloadRecords: vi.fn() }))
vi.mock('../../utils/file-download', () => ({ saveDownloadBlob: vi.fn() }))

describe('MyDownloadsView', () => {
  beforeEach(() => {
    session.setSession('download-view-token', {
      userId: 10001,
      username: '20260001',
      nickname: '张三',
      email: null,
      role: 1,
      status: 1,
    })
    vi.mocked(getMyDownloadRecords).mockResolvedValue({
      records: [{
        downloadRecordId: 60001,
        resourceId: 20001,
        title: '数据结构期末复习提纲',
        fileId: 30001,
        downloadStatus: 1,
        createdAt: '2026-07-02T14:00:00',
      }],
      pageNo: 1,
      pageSize: 10,
      total: 1,
      pages: 1,
    })
    vi.mocked(createDownloadRecord).mockResolvedValue({ downloadRecordId: 60002, resourceId: 20001, fileId: 30001, downloadUrl: '/api/v1/download-records/60002/file', expireSeconds: null, counted: false })
    vi.mocked(downloadFile).mockResolvedValue({ blob: new Blob(['file']), fileName: '数据结构.pdf', contentType: 'application/pdf' })
  })

  afterEach(() => {
    session.clearSession()
  })

  it('应展示下载记录，并按两步流程再次下载', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/me/downloads', name: 'my-downloads', component: MyDownloadsView },
        { path: '/resources/:resourceId', name: 'resource-detail', component: { template: '<div>详情</div>' } },
        { path: '/login', name: 'login', component: { template: '<div>登录</div>' } },
      ],
    })
    await router.push('/me/downloads')
    await router.isReady()

    const wrapper = mount(MyDownloadsView, { global: { plugins: [ElementPlus, router] } })
    await flushPromises()

    expect(getMyDownloadRecords).toHaveBeenCalledWith({ pageNo: 1, pageSize: 10 })
    expect(wrapper.text()).toContain('数据结构期末复习提纲')
    expect(wrapper.text()).toContain('成功')
    await wrapper.get('[data-test="redownload-20001"]').trigger('click')
    await flushPromises()
    expect(createDownloadRecord).toHaveBeenCalledWith(20001)
    expect(downloadFile).toHaveBeenCalledWith(60002)
    expect(saveDownloadBlob).toHaveBeenCalledWith(expect.any(Blob), '数据结构.pdf')
    expect(wrapper.text()).toContain('重复下载未重复计入统计')
  })
})
