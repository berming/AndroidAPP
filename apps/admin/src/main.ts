import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { router } from './router'
import { useAuthStore } from './stores/auth'

async function bootstrap() {
  const app = createApp(App)
  app.use(createPinia())
  app.use(ElementPlus, { locale: zhCn })

  // 注册所有 Element Plus 图标为全局组件，让 <el-icon><user /></el-icon> 这样的
  // 动态使用方式都能解析（AdminLayout 菜单图标用了 <component :is="iconName" />）
  for (const [name, component] of Object.entries(ElementPlusIconsVue)) {
    app.component(name, component as any)
  }

  // pr-reviewer P1：必须在 router 初次解析 navigation 之前完成 /me 拉取，
  // 否则带有效 cookie 的用户刷新页面也会被 beforeEach 守卫当成未登录踢回
  // /login（user 还是 null）。这里先拉一次 /me（401 也 OK，user 维持 null）
  // 再 use router + mount。
  await useAuthStore().fetchMe()

  app.use(router)
  await router.isReady()
  app.mount('#app')
}

bootstrap()
