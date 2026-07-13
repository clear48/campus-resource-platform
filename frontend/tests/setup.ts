import { afterEach } from 'vitest'

// 保持组件测试之间的 DOM 隔离，避免后续页面测试相互污染。
afterEach(() => {
  document.body.innerHTML = ''
})
