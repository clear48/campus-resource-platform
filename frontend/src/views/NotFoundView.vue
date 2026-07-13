<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

/** 未命中固定路由时，统一回到当前已实现的首页。 */
function backToHome() {
  router.push('/')
}
</script>

<template>
  <main class="not-found-view">
    <el-result icon="warning" title="页面不存在" sub-title="当前地址尚未配置为前端演示页面。">
      <template #extra>
        <el-button type="primary" @click="backToHome">返回首页</el-button>
      </template>
    </el-result>
  </main>
</template>
