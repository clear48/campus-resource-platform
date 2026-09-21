<script setup lang="ts">
import { computed } from 'vue'
import { session } from '../state/session'

// 固定导航只根据当前登录态展示入口，避免为演示项目引入动态菜单配置。
const isLoggedIn = session.isLoggedIn
const isAdmin = computed(() => session.currentUser.value?.role === 2)
</script>

<template>
  <el-container class="default-layout">
    <el-header class="default-layout__header">
      <RouterLink class="brand" to="/" aria-label="校园资料共享平台首页">
        <span class="brand__mark" aria-hidden="true">C</span>
        <span class="brand__copy">
          <strong>校园资料共享平台</strong>
          <small>Campus Knowledge Hub</small>
        </span>
      </RouterLink>

      <nav class="default-layout__navigation" aria-label="主导航">
        <RouterLink class="header-link" to="/search">资料搜索</RouterLink>
        <template v-if="isLoggedIn">
          <RouterLink class="header-link" to="/upload">上传资料</RouterLink>
          <RouterLink class="header-link" to="/me/uploads">我的上传</RouterLink>
          <RouterLink class="header-link" to="/me/favorites">我的收藏</RouterLink>
          <RouterLink class="header-link" to="/me/downloads">我的下载</RouterLink>
        </template>
      </nav>

      <nav class="default-layout__account" aria-label="用户导航">
        <template v-if="isLoggedIn">
          <RouterLink class="header-link" to="/me/profile">个人信息</RouterLink>
          <RouterLink v-if="isAdmin" class="header-link header-link--accent" to="/admin/reviews">管理员入口</RouterLink>
        </template>
        <template v-else>
          <RouterLink class="header-link" to="/login">登录</RouterLink>
          <RouterLink class="header-link header-link--button" to="/register">注册</RouterLink>
        </template>
        <span class="default-layout__demo-badge">前端演示</span>
      </nav>
    </el-header>
    <el-main class="default-layout__main">
      <RouterView />
    </el-main>
  </el-container>
</template>
