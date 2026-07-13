<template>
  <el-container class="admin-layout">
    <el-header class="admin-layout__header">
      <RouterLink class="brand" to="/">校园资料共享平台</RouterLink>
      <el-space>
        <RouterLink class="header-link" to="/admin/reviews">审核管理</RouterLink>
        <el-tag type="warning" effect="plain">管理员演示</el-tag>
      </el-space>
    </el-header>
    <el-main class="admin-layout__main">
      <RouterView />
    </el-main>
  </el-container>
</template>
