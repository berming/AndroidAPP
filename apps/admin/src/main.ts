import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { router } from './router'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

// 注册所有 Element Plus 图标为全局组件，让 <el-icon><user /></el-icon> 这样的
// 动态使用方式都能解析（AdminLayout 菜单图标用了 <component :is="iconName" />）
for (const [name, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, component as any)
}

app.mount('#app')
