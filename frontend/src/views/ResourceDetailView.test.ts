import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ResourceDetailView from './ResourceDetailView.vue'
import { session } from '../state/session'

const { getResourceDetail } = vi.hoisted(() => ({ getResourceDetail: vi.fn() }))
const { addFavorite, getFavoriteStatus, removeFavorite } = vi.hoisted(() => ({
  addFavorite: vi.fn(),
  getFavoriteStatus: vi.fn(),
  removeFavorite: vi.fn(),
}))
const { createDownloadRecord, downloadFile } = vi.hoisted(() => ({ createDownloadRecord: vi.fn(), downloadFile: vi.fn() }))
const { saveDownloadBlob } = vi.hoisted(() => ({ saveDownloadBlob: vi.fn() }))

vi.mock('../api/resources', () => ({ getResourceDetail }))
vi.mock('../api/favorites', () => ({ addFavorite, getFavoriteStatus, removeFavorite }))
vi.mock('../api/downloads', () => ({ createDownloadRecord, downloadFile }))
vi.mock('../utils/file-download', () => ({ saveDownloadBlob }))

describe('ResourceDetailView', () => {
  beforeEach(() => {
    session.clearSession()
    getResourceDetail.mockResolvedValue({
      resourceId: 20001,
      title: '数据结构期末复习提纲',
      description: '覆盖排序、树、图等重点内容',
      categoryId: 10,
      categoryName: '计算机基础',
      courseName: '数据结构',
      resourceType: 2,
      tags: ['数据结构', '复习'],
      status: 1,
      downloadCount: 128,
      favoriteCount: 35,
      hotScore: 745,
      createdAt: '2026-07-02T10:00:00',
      favorited: null,
    })
    getFavoriteStatus.mockResolvedValue({ resourceId: 20001, favorited: false })
    addFavorite.mockResolvedValue({ resourceId: 20001, favorited: true, duplicateIgnored: false, favoriteCount: 36, hotScoreDelta: 0 })
    removeFavorite.mockResolvedValue({ resourceId: 20001, favorited: false, duplicateIgnored: false, favoriteCount: 35, hotScoreDelta: 0 })
    createDownloadRecord.mockResolvedValue({ downloadRecordId: 60001, resourceId: 20001, fileId: 30001, downloadUrl: '/api/v1/download-records/60001/file', expireSeconds: null, counted: false })
    downloadFile.mockResolvedValue({ blob: new Blob(['file']), fileName: '数据结构.pdf', contentType: 'application/pdf' })
  })

  it('应允许游客按资料 ID 加载并展示只读详情', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/resources/:resourceId', name: 'resource-detail', component: ResourceDetailView }],
    })
    await router.push('/resources/20001')
    await router.isReady()

    const wrapper = mount(ResourceDetailView, {
      global: {
        plugins: [ElementPlus, router],
      },
    })
    await flushPromises()

    expect(getResourceDetail).toHaveBeenCalledWith(20001)
    expect(wrapper.text()).toContain('数据结构期末复习提纲')
    expect(wrapper.text()).toContain('计算机基础')
    expect(wrapper.text()).not.toContain('立即下载')
    expect(wrapper.text()).not.toContain('收藏资料')
  })

  it('登录用户应查询收藏状态并能收藏资料', async () => {
    session.setSession('favorite-token', {
      userId: 10001,
      username: '20260001',
      nickname: '张三',
      email: null,
      role: 1,
      status: 1,
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/resources/:resourceId', name: 'resource-detail', component: ResourceDetailView },
        { path: '/login', name: 'login', component: { template: '<div>登录</div>' } },
      ],
    })
    await router.push('/resources/20001')
    await router.isReady()

    const wrapper = mount(ResourceDetailView, { global: { plugins: [ElementPlus, router] } })
    await flushPromises()
    await wrapper.get('[data-test="favorite-button"]').trigger('click')
    await flushPromises()

    expect(getFavoriteStatus).toHaveBeenCalledWith(20001)
    expect(addFavorite).toHaveBeenCalledWith(20001)
    expect(wrapper.text()).toContain('取消收藏')
    expect(wrapper.text()).toContain('36')
  })

  it('登录用户应按两步顺序下载并展示后端统计结果', async () => {
    session.setSession('download-token', { userId: 10001, username: '20260001', nickname: '张三', email: null, role: 1, status: 1 })
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/resources/:resourceId', name: 'resource-detail', component: ResourceDetailView }] })
    await router.push('/resources/20001')
    await router.isReady()
    const wrapper = mount(ResourceDetailView, { global: { plugins: [ElementPlus, router] } })
    await flushPromises()
    await wrapper.get('[data-test="download-button"]').trigger('click')
    await flushPromises()

    expect(createDownloadRecord).toHaveBeenCalledWith(20001)
    expect(downloadFile).toHaveBeenCalledWith(60001)
    expect(saveDownloadBlob).toHaveBeenCalledWith(expect.any(Blob), '数据结构.pdf')
    expect(wrapper.text()).toContain('重复下载未重复计入统计')
  })
})
