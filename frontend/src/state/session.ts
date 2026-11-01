import { computed, readonly, ref } from 'vue'
import type { UserProfile } from '../types/user'
import type { LoginResult } from '../types/auth'

const storageKey = 'crp.auth-session'
const tokenKey = 'crp.access-token'
const userKey = 'crp.current-user'

/** generation 只在登录/退出时改变；刷新保留它以识别晚到响应。 */
export interface SessionSnapshot {
  generation: string
  accessToken: string | null
  refreshToken: string | null
  user: UserProfile | null
}
const accessToken = ref<string | null>(null)
const refreshToken = ref<string | null>(null)
const currentUser = ref<UserProfile | null>(null)
const logoutPending = ref(false)
let state: SessionSnapshot = { generation: 'empty', accessToken: null, refreshToken: null, user: null }
let localQueue: Promise<unknown> = Promise.resolve()
const generation = () => globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`

function apply(next: SessionSnapshot) {
  state = next
  accessToken.value = next.accessToken
  refreshToken.value = next.refreshToken
  currentUser.value = next.user
}

/** 单 JSON 原子保存完整凭据，旧键只作展示兼容镜像。 */
function persist(next: SessionSnapshot) {
  localStorage.setItem(storageKey, JSON.stringify(next))
  if (next.accessToken) localStorage.setItem(tokenKey, next.accessToken)
  else localStorage.removeItem(tokenKey)
  if (next.user) localStorage.setItem(userKey, JSON.stringify(next.user))
  else localStorage.removeItem(userKey)
  apply(next)
}

function hydrateSession() {
  const stored = localStorage.getItem(storageKey)
  if (stored) {
    try {
      const parsed = JSON.parse(stored) as SessionSnapshot
      if (typeof parsed.generation !== 'string' ||
          !(parsed.accessToken === null || typeof parsed.accessToken === 'string') ||
          !(parsed.refreshToken === null || typeof parsed.refreshToken === 'string')) throw new Error('invalid session')
      apply(parsed)
      return
    } catch {
      // 损坏主记录不能回退到可能陈旧的镜像。
      apply({ generation: 'invalid-session', accessToken: null, refreshToken: null, user: null })
      return
    }
  }
  const token = localStorage.getItem(tokenKey)
  let user: UserProfile | null = null
  try { user = JSON.parse(localStorage.getItem(userKey) ?? 'null') as UserProfile | null }
  catch { localStorage.removeItem(userKey) }
  apply({ generation: token ? `legacy:${token}` : 'empty', accessToken: token, refreshToken: null, user })
}

function snapshot(): SessionSnapshot { hydrateSession(); return { ...state } }
function matches(expected: SessionSnapshot, credentials = true) {
  const now = snapshot()
  return now.generation === expected.generation && (!credentials ||
    (now.accessToken === expected.accessToken && now.refreshToken === expected.refreshToken))
}

/** 锁覆盖 HTTP 和提交；无 Web Locks 时退化为同页串行，仍保留快照保护。 */
export function withSessionLock<T>(action: () => Promise<T>): Promise<T> {
  const run = () => globalThis.navigator?.locks
    ? navigator.locks.request('crp.auth-session', action) : action()
  const result = localQueue.then(run, run)
  localQueue = result.catch(() => undefined)
  return result
}
/** 同步写入原语仅供锁内提交或旧调用方/测试使用。 */
function setSession(token: string, user: UserProfile, refresh: string | null = null) {
  persist({ generation: generation(), accessToken: token, refreshToken: refresh, user })
}
function clearSession(expected?: SessionSnapshot) {
  if (expected && !matches(expected)) return false
  persist({ generation: generation(), accessToken: null, refreshToken: null, user: null })
  return true
}
/** 非锁内的失效响应必须先取得同一跨页锁，再重检原请求凭据。 */
async function clearSessionIfCurrent(expected: SessionSnapshot) {
  return withSessionLock(async () => clearSession(expected))
}
async function commitLogin(pair: LoginResult, expected: SessionSnapshot) {
  return withSessionLock(async () => {
    if (logoutPending.value || !matches(expected)) return false
    setSession(pair.accessToken, pair.user, pair.refreshToken ?? null)
    return true
  })
}
function commitRefresh(pair: LoginResult, expected: SessionSnapshot) {
  if (!matches(expected)) return false
  persist({ generation: expected.generation, accessToken: pair.accessToken,
    refreshToken: pair.refreshToken ?? null, user: pair.user })
  return true
}
/** 用户资料更新不触碰已轮换的凭据，也不能跨账号回写。 */
async function updateCurrentUser(user: UserProfile, expected: SessionSnapshot) {
  return withSessionLock(async () => {
    if (!matches(expected, false)) return false
    persist({ ...state, user })
    return true
  })
}
hydrateSession()
if (typeof window !== 'undefined') window.addEventListener('storage', (event) => {
  if (event.key === null || [storageKey, tokenKey, userKey].includes(event.key)) hydrateSession()
})
export const session = {
  accessToken: readonly(accessToken), refreshToken: readonly(refreshToken), currentUser: readonly(currentUser),
  logoutPending, isLoggedIn: computed(() => Boolean(accessToken.value)),
  hydrateSession, snapshot, matches, setSession, clearSession, clearSessionIfCurrent, commitLogin, commitRefresh, updateCurrentUser,
}
