import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './styles/index.css'
import App from './App.vue'

// 组件库只在入口注册，页面后续按需使用表单、表格和提示等基础组件。
const app = createApp(App)

app.use(ElementPlus)
app.mount('#app')
