<template>
  <el-container class="default-layout">
    <el-header class="default-layout__header">
      <RouterLink class="brand" to="/">校园资料共享平台</RouterLink>
      <el-space>
        <RouterLink class="header-link" to="/search">资料搜索</RouterLink>
        <RouterLink class="header-link" to="/me/profile">个人信息</RouterLink>
        <el-tag type="info" effect="plain">前端演示</el-tag>
      </el-space>
    </el-header>
    <el-main class="default-layout__main">
      <RouterView />
    </el-main>
  </el-container>
</template>
