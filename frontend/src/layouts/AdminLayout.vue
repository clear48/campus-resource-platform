<template>
  <el-container class="admin-layout">
    <aside class="admin-layout__sidebar">
      <RouterLink class="admin-brand" to="/" aria-label="返回校园资料共享平台首页">
        <span class="admin-brand__mark" aria-hidden="true">C</span>
        <span>
          <strong>校园资料平台</strong>
          <small>管理控制台</small>
        </span>
      </RouterLink>

      <nav class="admin-layout__navigation" aria-label="管理端导航">
        <RouterLink class="admin-nav-link" to="/admin/reviews"><span aria-hidden="true">审</span>审核管理</RouterLink>
        <RouterLink class="admin-nav-link" to="/admin/resources"><span aria-hidden="true">资</span>发布资料</RouterLink>
        <RouterLink class="admin-nav-link" to="/admin/rankings"><span aria-hidden="true">榜</span>排行榜运维</RouterLink>
      </nav>

      <div class="admin-layout__sidebar-footer">
        <span class="admin-layout__badge">管理员演示</span>
        <RouterLink class="admin-layout__back-link" to="/">← 返回前台</RouterLink>
      </div>
    </aside>

    <el-container class="admin-layout__workspace">
      <el-header class="admin-layout__header">
        <div>
          <p class="admin-layout__eyebrow">Administration</p>
          <strong>资料治理与运营</strong>
        </div>
        <p>审核公开内容、维护发布状态与排行榜</p>
      </el-header>
      <el-main class="admin-layout__main">
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>
