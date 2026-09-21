<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { login } from '../api/auth'
import type { LoginRequest } from '../types/auth'
import { ApiBusinessError } from '../utils/request'

const router = useRouter()
const route = useRoute()
const loginFormRef = ref<FormInstance>()
const loading = ref(false)
const errorMessage = ref('')

const loginForm = reactive<LoginRequest>({
  username: '',
  password: '',
})

const loginRules: FormRules<LoginRequest> = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

/**
 * 仅允许单斜杠开头的站内路径，阻断协议相对地址、反斜杠和换行造成的开放重定向。
 */
function resolveRedirectTarget(redirect: unknown): string {
  if (typeof redirect !== 'string' || !redirect.startsWith('/') || redirect.startsWith('//') || /[\\\r\n]/.test(redirect)) {
    return '/'
  }

  return redirect
}

/**
 * 登录成功后先保存最小会话数据，T11 会把存取和失效清理统一收口到会话状态模块。
 */
async function submitLogin() {
  const valid = await loginFormRef.value?.validate().catch(() => false)

  if (!valid || loading.value) {
    return
  }

  loading.value = true
  errorMessage.value = ''

  try {
    const loginResult = await login({ ...loginForm })

    localStorage.setItem('crp.access-token', loginResult.accessToken)
    localStorage.setItem('crp.current-user', JSON.stringify(loginResult.user))
    await router.push(resolveRedirectTarget(route.query.redirect))
  } catch (error) {
    errorMessage.value = error instanceof ApiBusinessError ? error.message : '登录失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-shell" aria-labelledby="login-title">
      <div class="auth-shell__story">
        <RouterLink class="auth-shell__brand" to="/">校园资料共享平台</RouterLink>
        <div>
          <p class="eyebrow">Welcome Back</p>
          <h2>连接知识，也连接每一次认真学习</h2>
          <p>登录后可以上传资料、管理个人收藏与下载记录，让优质内容在校园内持续流动。</p>
        </div>
        <ul class="auth-shell__trust-list">
          <li>资料审核后公开</li>
          <li>JWT 保护登录会话</li>
        </ul>
      </div>

      <el-card class="auth-card" shadow="never">
        <RouterLink class="auth-card__home-link" to="/">← 返回首页</RouterLink>
        <h1 id="login-title">登录平台</h1>
        <p class="auth-card__description">登录后可上传、收藏和下载资料。</p>

        <el-alert v-if="errorMessage" class="auth-card__alert" type="error" :title="errorMessage" :closable="false" show-icon />

        <el-form ref="loginFormRef" :model="loginForm" :rules="loginRules" label-position="top" @submit.prevent="submitLogin">
          <el-form-item label="账号" prop="username">
            <el-input v-model="loginForm.username" placeholder="请输入账号" autocomplete="username" />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input v-model="loginForm.password" placeholder="请输入密码" type="password" autocomplete="current-password" show-password />
          </el-form-item>
          <el-button class="auth-card__submit" type="primary" native-type="submit" :loading="loading">登录</el-button>
        </el-form>

        <p class="auth-card__link">还没有账号？<RouterLink to="/register">去注册</RouterLink></p>
      </el-card>
    </section>
  </main>
</template>
