# 沟通牌 · Admin SPA

Vue 3 + Element Plus 后台管理 UI。挂在 `https://bermin.cn/admin/`。

## 本地开发

```bash
cd apps/admin
npm ci            # 首次拉依赖
npm run dev       # 启 Vite dev server :5173，代理 /admin/api → :8080 Ktor

# 另起一个终端跑服务端
./gradlew :server:run
```

启服务端时 `application.conf` 把 `admin.cookieSecure` 设 false（开发环境
HTTP 而非 HTTPS）；正式部署该值为 true。

## 生产构建

```bash
cd apps/admin
npm run build     # 输出 apps/admin/dist/
```

部署：`.github/workflows/deploy.yml` 在 push 到 main 时 rsync `dist/` 到
服务器 `/var/www/communication-card-admin/`。

## 目录结构

```
src/
├── main.ts                      Vue createApp + 注册插件
├── App.vue                      <router-view />
├── router/index.ts              路由表 + beforeEach auth guard
├── stores/auth.ts               Pinia useAuthStore
├── api/                         axios 包装：http / auth / monitor / alerts / games
├── layouts/AdminLayout.vue      Header + Sider + Content
├── views/
│   ├── Login.vue                登录页
│   ├── Dashboard.vue            数据总览（模块 1）
│   ├── Rooms.vue · RoomDetail.vue 房间监控
│   ├── Players.vue              玩家列表
│   ├── Sessions.vue             会话列表
│   ├── Games.vue                历史游戏
│   └── Alerts.vue               告警 + ack
└── components/AlertWatcher.vue  全局轮询，新告警 toast
```

## 权限模型

后端硬编码两个角色：

| 角色 | 拥有的权限 |
|------|----------|
| `SUPER_ADMIN` | MONITOR_READ / ALERT_ACK / GAME_HISTORY_READ / USER_MANAGE / PLAYER_DISCIPLINE / CONFIG_WRITE |
| `OPS_ADMIN`   | MONITOR_READ / ALERT_ACK / GAME_HISTORY_READ |

MVP（模块 1+2）只用前 3 个权限位；模块 4 / 6 / 7 路由暂未暴露。
前端 `stores/auth.ts` 的 `hasPermission()` 控制菜单可见性，但服务端 RBAC
是真正的权限闸门。
