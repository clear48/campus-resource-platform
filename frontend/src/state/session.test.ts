import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { session } from './session'
const user = { userId: 1, username: 'student', nickname: '学生', email: null, role: 1, status: 1 }
beforeEach(() => { localStorage.clear(); session.logoutPending.value = false })
afterEach(() => vi.unstubAllGlobals())
describe('双Token会话', () => {
  it('主记录原子保存双凭据，资料更新不能丢失已轮换的Refresh', async () => {
    session.setSession('old', user, 'refresh')
    const expected = session.snapshot()
    session.commitRefresh({ accessToken: 'new', refreshToken: 'new-refresh', user, expiresIn: 900, tokenType: 'Bearer' }, expected)
    await session.updateCurrentUser({ ...user, nickname: '新昵称' }, expected)
    const stored = JSON.parse(localStorage.getItem('crp.auth-session') ?? '{}')
    expect(stored.refreshToken).toBe('new-refresh')
    expect(stored.accessToken).toBe('new')
    expect(stored.generation).toBe(expected.generation)
  })
  it('旧资料响应或旧登录结果不能覆盖新账号或已退出状态', async () => {
    session.setSession('old', user, 'refresh')
    const expected = session.snapshot()
    session.clearSession()
    expect(await session.updateCurrentUser(user, expected)).toBe(false)
    expect(await session.commitLogin({ accessToken: 'late', user, expiresIn: 900, tokenType: 'Bearer' }, expected)).toBe(false)
    expect(session.accessToken.value).toBeNull()
  })
  it('资料更新取得跨页锁后重新读取已经轮换的凭据', async () => {
    session.setSession('old', user, 'refresh')
    const expected = session.snapshot()
    vi.stubGlobal('navigator', { locks: { request: async (_name: string, action: () => Promise<unknown>) => {
      session.commitRefresh({ accessToken: 'from-other-tab', refreshToken: 'from-other-refresh',
        user, expiresIn: 900, tokenType: 'Bearer' }, expected)
      return action()
    } } })
    await session.updateCurrentUser({ ...user, nickname: '新昵称' }, expected)
    expect(session.accessToken.value).toBe('from-other-tab')
    expect(session.refreshToken.value).toBe('from-other-refresh')
  })
  it('旧响应清理取得锁后重检，不清除锁等待期间的新账号', async () => {
    session.setSession('old', user, 'refresh')
    const expected = session.snapshot()
    vi.stubGlobal('navigator', { locks: { request: async (_name: string, action: () => Promise<unknown>) => {
      session.setSession('new-account', { ...user, userId: 2 }, 'new-refresh')
      return action()
    } } })
    expect(await session.clearSessionIfCurrent(expected)).toBe(false)
    expect(session.accessToken.value).toBe('new-account')
  })
  it('兼容旧本地Token但不凭空生成Refresh', () => {
    localStorage.setItem('crp.access-token', 'legacy')
    session.hydrateSession()
    expect(session.accessToken.value).toBe('legacy')
    expect(session.refreshToken.value).toBeNull()
  })
  it('storage事件恢复其他标签页的主记录', () => {
    session.setSession('old', user, 'refresh')
    const stored = JSON.parse(localStorage.getItem('crp.auth-session') ?? '{}')
    localStorage.setItem('crp.auth-session', JSON.stringify({ ...stored, accessToken: 'from-other-tab' }))
    window.dispatchEvent(new StorageEvent('storage', { key: 'crp.auth-session' }))
    expect(session.accessToken.value).toBe('from-other-tab')
  })
})
