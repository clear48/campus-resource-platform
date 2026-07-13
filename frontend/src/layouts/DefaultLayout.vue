<template>
  <el-container class="default-layout">
    <el-header class="default-layout__header">
      <RouterLink class="brand" to="/">校园资料共享平台</RouterLink>
      <el-tag type="info" effect="plain">前端演示</el-tag>
    </el-header>
    <el-main class="default-layout__main">
      <RouterView />
    </el-main>
  </el-container>
</template>
