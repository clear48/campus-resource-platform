<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { logout } from '../../api/auth'
import { getCurrentUser } from '../../api/users'
import { session } from '../../state/session'
import { getUserRoleLabel } from '../../types/enums'
import { ApiBusinessError } from '../../utils/request'

const router = useRouter()
const loading = ref(false)
const logoutLoading = ref(false)
const errorMessage = ref('')

/** 页面进入时以当前用户接口为准刷新资料，避免仅相信浏览器缓存。 */
async function loadCurrentUser() {
  session.hydrateSession()

  if (!session.isLoggedIn.value) {
    await router.replace('/login')
    return
  }

  loading.value = true
  errorMessage.value = ''

  try {
    const user = await getCurrentUser()
    const token = session.accessToken.value

    if (token) {
      session.setSession(token, user)
    }
  } catch (error) {
    errorMessage.value = error instanceof ApiBusinessError ? error.message : '获取用户信息失败'
  } finally {
    loading.value = false
  }
}

/** 退出必须先调用后端，使 JWT 进入 Redis 黑名单后再清理浏览器会话。 */
async function submitLogout() {
  if (logoutLoading.value) {
    return
  }

  logoutLoading.value = true
  errorMessage.value = ''

  try {
    await logout()
    session.clearSession()
    ElMessage.success('已退出登录')
    await router.replace('/login')
  } catch (error) {
    errorMessage.value = error instanceof ApiBusinessError ? error.message : '退出登录失败'
  } finally {
    logoutLoading.value = false
  }
}

function goToAdmin() {
  router.push('/admin/reviews')
}

onMounted(loadCurrentUser)
</script>

<template>
  <section class="profile-view">
    <el-card shadow="never">
      <template #header>
        <div class="profile-view__header">
          <span>个人信息</span>
          <el-button text :loading="loading" @click="loadCurrentUser">刷新</el-button>
        </div>
      </template>

      <el-alert v-if="errorMessage" class="profile-view__alert" type="error" :title="errorMessage" :closable="false" show-icon />

      <el-skeleton :loading="loading" animated :rows="4">
        <el-descriptions v-if="session.currentUser.value" :column="1" border>
          <el-descriptions-item label="用户 ID">{{ session.currentUser.value.userId }}</el-descriptions-item>
          <el-descriptions-item label="账号">{{ session.currentUser.value.username }}</el-descriptions-item>
          <el-descriptions-item label="昵称">{{ session.currentUser.value.nickname }}</el-descriptions-item>
          <el-descriptions-item label="邮箱">{{ session.currentUser.value.email || '--' }}</el-descriptions-item>
          <el-descriptions-item label="角色">{{ getUserRoleLabel(session.currentUser.value.role) }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ session.currentUser.value.status }}</el-descriptions-item>
        </el-descriptions>
      </el-skeleton>

      <el-space class="profile-view__actions" wrap>
        <el-button v-if="session.currentUser.value?.role === 2" @click="goToAdmin">进入管理端</el-button>
        <el-button type="danger" plain :loading="logoutLoading" @click="submitLogout">退出登录</el-button>
      </el-space>
    </el-card>
  </section>
</template>
