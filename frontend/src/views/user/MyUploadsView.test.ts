import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getMyResources } from '../../api/users'
import { session } from '../../state/session'
import MyUploadsView from './MyUploadsView.vue'

vi.mock('../../api/users', () => ({ getMyResources: vi.fn() }))

describe('MyUploadsView', () => {
  beforeEach(() => {
    session.setSession('upload-list-token', {
      userId: 10001,
      username: '20260001',
      nickname: '张三',
      email: null,
      role: 1,
      status: 1,
    })
    vi.mocked(getMyResources).mockResolvedValue({
      records: [{
        resourceId: 20002,
        title: '操作系统实验报告模板',
        courseName: '操作系统',
        status: 2,
        rejectReason: '请补充实验截图',
        offlineReason: null,
        createdAt: '2026-07-02T10:30:00',
      }],
      pageNo: 1,
      pageSize: 10,
      total: 1,
      pages: 1,
    })
  })

  afterEach(() => {
    session.clearSession()
  })

  it('应加载我的上传资料并展示审核原因', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/me/uploads', name: 'my-uploads', component: MyUploadsView },
        { path: '/login', name: 'login', component: { template: '<div>登录</div>' } },
      ],
    })
    await router.push('/me/uploads')
    await router.isReady()

    const wrapper = mount(MyUploadsView, {
      global: { plugins: [ElementPlus, router] },
    })
    await flushPromises()

    expect(getMyResources).toHaveBeenCalledWith({ status: undefined, pageNo: 1, pageSize: 10 })
    expect(wrapper.text()).toContain('操作系统实验报告模板')
    expect(wrapper.text()).toContain('已拒绝')
    expect(wrapper.text()).toContain('请补充实验截图')
  })
})
