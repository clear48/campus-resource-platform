import { computed, readonly, ref } from 'vue'
import type { UserProfile } from '../types/user'

const tokenStorageKey = 'crp.access-token'
const userStorageKey = 'crp.current-user'

function readStoredUser(): UserProfile | null {
  const storedUser = localStorage.getItem(userStorageKey)

  if (!storedUser) {
    return null
  }

  try {
    return JSON.parse(storedUser) as UserProfile
  } catch {
    // 本地缓存损坏时主动清理，避免后续页面反复解析失败。
    localStorage.removeItem(userStorageKey)
    return null
  }
}

const accessToken = ref(localStorage.getItem(tokenStorageKey))
const currentUser = ref<UserProfile | null>(readStoredUser())

/**
 * 登录页在 T09 已写入浏览器存储；每次受保护请求前同步一次，确保无需刷新页面也能读取最新 Token。
 */
function hydrateSession() {
  accessToken.value = localStorage.getItem(tokenStorageKey)
  currentUser.value = readStoredUser()
}

function setSession(token: string, user: UserProfile) {
  accessToken.value = token
  currentUser.value = user
  localStorage.setItem(tokenStorageKey, token)
  localStorage.setItem(userStorageKey, JSON.stringify(user))
}

function clearSession() {
  accessToken.value = null
  currentUser.value = null
  localStorage.removeItem(tokenStorageKey)
  localStorage.removeItem(userStorageKey)
}

export const session = {
  accessToken: readonly(accessToken),
  currentUser: readonly(currentUser),
  isLoggedIn: computed(() => Boolean(accessToken.value)),
  hydrateSession,
  setSession,
  clearSession,
}
