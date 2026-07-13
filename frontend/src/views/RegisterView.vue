<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { register } from '../api/auth'
import type { RegisterRequest } from '../types/auth'
import { ApiBusinessError } from '../utils/request'

const router = useRouter()
const registerFormRef = ref<FormInstance>()
const loading = ref(false)
const errorMessage = ref('')

const registerForm = reactive({
  username: '',
  password: '',
  nickname: '',
  email: '',
  phone: '',
})

const registerRules: FormRules<RegisterRequest> = {
  username: [{ required: true, max: 50, message: '请输入不超过 50 个字符的账号', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 50, message: '密码长度应为 8 到 50 个字符', trigger: 'blur' },
  ],
  nickname: [{ required: true, max: 50, message: '请输入不超过 50 个字符的昵称', trigger: 'blur' }],
  email: [
    { max: 100, message: '邮箱不能超过 100 个字符', trigger: 'blur' },
    { type: 'email', message: '请输入合法邮箱', trigger: 'blur' },
  ],
  phone: [{ pattern: /^$|^1[3-9]\d{9}$/, message: '请输入合法中国大陆手机号', trigger: 'blur' }],
}

/**
 * 可选字段为空时不传给后端，避免把空字符串误判为格式非法的邮箱或手机号。
 */
function buildRegisterPayload(): RegisterRequest {
  return {
    username: registerForm.username,
    password: registerForm.password,
    nickname: registerForm.nickname,
    email: registerForm.email || undefined,
    phone: registerForm.phone || undefined,
  }
}

async function submitRegister() {
  const valid = await registerFormRef.value?.validate().catch(() => false)

  if (!valid || loading.value) {
    return
  }

  loading.value = true
  errorMessage.value = ''

  try {
    await register(buildRegisterPayload())
    ElMessage.success('注册成功，请登录')
    await router.push('/login')
  } catch (error) {
    errorMessage.value = error instanceof ApiBusinessError ? error.message : '注册失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <el-card class="auth-card" shadow="never">
      <h1>注册账号</h1>
      <p class="auth-card__description">创建账号后可登录平台并提交课程资料。</p>

      <el-alert v-if="errorMessage" class="auth-card__alert" type="error" :title="errorMessage" :closable="false" show-icon />

      <el-form ref="registerFormRef" :model="registerForm" :rules="registerRules" label-position="top" @submit.prevent="submitRegister">
        <el-form-item label="账号" prop="username">
          <el-input v-model="registerForm.username" placeholder="建议使用学号" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="registerForm.password" placeholder="8 到 50 个字符" type="password" autocomplete="new-password" show-password />
        </el-form-item>
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="registerForm.nickname" placeholder="请输入昵称" autocomplete="name" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="registerForm.email" placeholder="选填" autocomplete="email" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="registerForm.phone" placeholder="选填" autocomplete="tel" />
        </el-form-item>
        <el-button class="auth-card__submit" type="primary" native-type="submit" :loading="loading">注册</el-button>
      </el-form>

      <p class="auth-card__link">已有账号？<RouterLink to="/login">去登录</RouterLink></p>
    </el-card>
  </main>
</template>
