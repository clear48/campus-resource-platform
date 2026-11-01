import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { httpClient, request } from './request'
import { session } from '../state/session'
import { logout } from '../api/auth'
import { downloadFile } from '../api/downloads'

const user = { userId: 1, username: 'student', nickname: '学生', email: null, role: 1, status: 1 }
const pair = { accessToken: 'next-access', refreshToken: 'next-refresh', tokenType: 'Bearer', expiresIn: 900,
  refreshExpiresIn: 1000, user }
const ok = (data: unknown) => ({ code: 0, message: 'success', data })
const bad = (code: number) => ({ code, message: 'error', data: null })
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}
let mock: MockAdapter
beforeEach(() => {
  localStorage.clear()
  session.logoutPending.value = false
  session.setSession('old-access', user, 'old-refresh')
  mock = new MockAdapter(httpClient)
})
afterEach(() => { mock.restore(); session.clearSession(); vi.unstubAllGlobals() })

describe('自动刷新编排', () => {
  it('并发过期只刷新一次，每个原请求最多重放一次', async () => {
    mock.onGet('/protected').reply((config) => config.headers?.Authorization === 'Bearer old-access'
      ? [401, bad(40103)] : [200, ok('done')])
    mock.onPost('/auth/refresh').reply(200, ok(pair))
    await expect(Promise.all([request.get('/protected'), request.get('/protected')])).resolves.toEqual(['done', 'done'])
    expect(mock.history.post.filter((c) => c.url === '/auth/refresh')).toHaveLength(1)
    expect(mock.history.get).toHaveLength(4)
  })

  it('延迟旧401只使用已轮换凭据重试，不再刷新', async () => {
    const delayed = deferred<[number, unknown]>()
    mock.onGet('/delayed').replyOnce(() => delayed.promise)
    mock.onGet('/delayed').reply(200, ok('done'))
    const pending = request.get('/delayed')
    await vi.waitFor(() => expect(mock.history.get).toHaveLength(1))
    session.commitRefresh(pair, session.snapshot())
    delayed.resolve([401, bad(40103)])
    await expect(pending).resolves.toBe('done')
    expect(mock.history.post).toHaveLength(0)
  })

  it('Axios排队时同代凭据已刷新仍发送原快照并复用新凭据', async () => {
    mock.onGet('/queued').replyOnce(401, bad(40103))
    mock.onGet('/queued').reply(200, ok('done'))
    const expected = session.snapshot()
    const pending = request.get('/queued')
    session.commitRefresh(pair, expected)
    await expect(pending).resolves.toBe('done')
    expect(mock.history.get[0]?.headers?.Authorization).toBe('Bearer old-access')
    expect(mock.history.get[1]?.headers?.Authorization).toBe('Bearer next-access')
    expect(mock.history.post).toHaveLength(0)
  })

  it('Axios发送前切换账号时拒绝操作，不把原操作发给新账号', async () => {
    mock.onGet('/protected').reply(200, ok('unexpected'))
    const pending = request.get('/protected')
    session.setSession('other-account', { ...user, userId: 2 }, 'other-refresh')
    await expect(pending).rejects.toMatchObject({ code: 40105 })
    expect(mock.history.get).toHaveLength(0)
    expect(session.accessToken.value).toBe('other-account')
  })

  it('刷新网络失败保留会话，不误当成Refresh被撤销', async () => {
    mock.onGet('/protected').reply(401, bad(40103))
    mock.onPost('/auth/refresh').networkError()
    await expect(request.get('/protected')).rejects.toMatchObject({ code: -1 })
    expect(session.refreshToken.value).toBe('old-refresh')
  })

  it('重放仍过期时不循环刷新', async () => {
    mock.onGet('/protected').reply(401, bad(40103))
    mock.onPost('/auth/refresh').reply(200, ok(pair))
    await expect(request.get('/protected')).rejects.toMatchObject({ code: 40103 })
    expect(mock.history.get).toHaveLength(2)
    expect(mock.history.post).toHaveLength(1)
  })

  it.each([40101, 40102, 40105])('认证错误%d不刷新', async (code) => {
    mock.onGet('/protected').reply(401, bad(code))
    await expect(request.get('/protected')).rejects.toMatchObject({ code })
    expect(mock.history.post).toHaveLength(0)
    expect(session.accessToken.value).toBeNull()
  })

  it.each([50001, 40104])('刷新失败%d只在凭据失效时清理', async (code) => {
    mock.onGet('/protected').reply(401, bad(40103))
    mock.onPost('/auth/refresh').reply(code === 50001 ? 500 : 401, bad(code))
    await expect(request.get('/protected')).rejects.toMatchObject({ code })
    expect(session.accessToken.value).toBe(code === 50001 ? 'old-access' : null)
  })

  it('旧业务失败不能清理新账号', async () => {
    const delayed = deferred<[number, unknown]>()
    mock.onGet('/protected').reply(() => delayed.promise)
    const pending = request.get('/protected')
    await vi.waitFor(() => expect(mock.history.get).toHaveLength(1))
    session.setSession('new-account', { ...user, userId: 2 }, 'new-account-refresh')
    delayed.resolve([401, bad(40102)])
    await expect(pending).rejects.toMatchObject({ code: 40102 })
    expect(session.accessToken.value).toBe('new-account')
  })

  it('晚到刷新成功不能覆盖后来登录的新账号', async () => {
    const delayed = deferred<[number, unknown]>()
    mock.onGet('/protected').reply(401, bad(40103))
    mock.onPost('/auth/refresh').reply(() => delayed.promise)
    const pending = request.get('/protected')
    await vi.waitFor(() => expect(mock.history.post).toHaveLength(1))
    session.setSession('new-account', { ...user, userId: 2 }, 'new-account-refresh')
    delayed.resolve([200, ok(pair)])
    await expect(pending).rejects.toMatchObject({ code: 40105 })
    expect(session.accessToken.value).toBe('new-account')
  })

  it('退出等待在途刷新提交后使用新Refresh撤销且不重放业务', async () => {
    const delayed = deferred<[number, unknown]>()
    mock.onGet('/protected').reply(401, bad(40103))
    mock.onPost('/auth/refresh').reply(() => delayed.promise)
    mock.onPost('/auth/logout').reply((config) => {
      expect(JSON.parse(config.data)).toEqual({ refreshToken: 'next-refresh' })
      return [200, ok(null)]
    })
    const pending = request.get('/protected').catch((error: unknown) => error)
    await vi.waitFor(() => expect(mock.history.post).toHaveLength(1))
    const exiting = logout()
    delayed.resolve([200, ok(pair)])
    await pending
    await expect(exiting).resolves.toBeNull()
    expect(mock.history.get).toHaveLength(1)
    expect(session.accessToken.value).toBeNull()
  })

  it('旧无Refresh会话Access过期时仍能完成本地退出', async () => {
    session.setSession('legacy-expired', user)
    mock.onPost('/auth/logout').reply(401, bad(40103))
    await expect(logout()).resolves.toBeNull()
    expect(session.accessToken.value).toBeNull()
  })

  it('退出网络或服务端失败保留会话以便重试', async () => {
    mock.onPost('/auth/logout').reply(500, bad(50001))
    await expect(logout()).rejects.toMatchObject({ code: 50001 })
    expect(session.refreshToken.value).toBe('old-refresh')
    expect(session.logoutPending.value).toBe(false)
  })

  it('跨页Web Lock内重读并复用其他页面的轮换结果', async () => {
    const expected = session.snapshot()
    const lock = vi.fn(async (_name: string, action: () => Promise<unknown>) => {
      session.commitRefresh(pair, expected)
      return action()
    })
    vi.stubGlobal('navigator', { locks: { request: lock } })
    mock.onGet('/protected').replyOnce(401, bad(40103))
    mock.onGet('/protected').reply(200, ok('done'))
    await expect(request.get('/protected')).resolves.toBe('done')
    expect(lock).toHaveBeenCalledWith('crp.auth-session', expect.any(Function))
    expect(mock.history.post).toHaveLength(0)
  })

  it('下载JWT到期可保留原票据刷新后仅重放一次', async () => {
    mock.onGet('/download-records/1/file').replyOnce(401,
      new Blob([JSON.stringify(bad(40103))], { type: 'application/json' }))
    mock.onGet('/download-records/1/file').reply(200, new Blob(['file']), { 'content-type': 'application/pdf' })
    mock.onPost('/auth/refresh').reply(200, ok(pair))
    await downloadFile(1, 'original-ticket')
    expect(mock.history.get).toHaveLength(2)
    expect(mock.history.get[1]?.headers?.['X-Download-Ticket']).toBe('original-ticket')
    expect(mock.history.get[1]?.headers?.Authorization).toBe('Bearer next-access')
  })

  it('下载票据40901不能触发刷新或重放也不清理登录态', async () => {
    mock.onGet('/download-records/1/file').reply(409,
      new Blob([JSON.stringify(bad(40901))], { type: 'application/json' }))
    await expect(downloadFile(1, 'used-ticket')).rejects.toMatchObject({ code: 40901 })
    expect(session.accessToken.value).toBe('old-access')
    expect(mock.history.get).toHaveLength(1)
    expect(mock.history.post).toHaveLength(0)
  })
})
