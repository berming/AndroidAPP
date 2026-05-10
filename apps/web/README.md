# 沟通牌 · Web 客户端（Compose Multiplatform / Wasm-JS）

浏览器版客户端。代码与 Android 客户端共享 `:shared` 模块（牌型规则 / 结算 /
AI / 协议 DTO），保证两端行为完全一致。

---

## ⚠️ 不能用 `file://` 双击打开

Wasm-JS 应用必须由 **HTTP server** 提供：浏览器在 `file://` 协议下会拒绝
fetch 同目录的 chunk（`135.js`/`273.js`/`skiko.js`）和 `.wasm` 文件
（CORS origin: null），加载会卡在"正在加载…"。

**验证现象**：DevTools → Console 出现 `Failed to fetch` /
`Access to ... has been blocked by CORS policy`。

---

## 怎么跑

### 1. 开发：dev server（热重载）

```bash
./gradlew :apps:web:wasmJsBrowserDevelopmentRun
```

浏览器自动打开 `http://localhost:8080`，改代码即时刷新。

### 2. 本地预览生产产物

```bash
./gradlew :apps:web:wasmJsBrowserDistribution
cd apps/web/build/dist/wasmJs/productionExecutable

# 任选一种：
python3 -m http.server 8000           # Chrome OK；Safari 可能因 wasm MIME 挂
npx http-server -c-1                  # 推荐：MIME 完整 + 禁缓存
```

打开 `http://localhost:8000`。

### 3. CI artifact

每次 PR 都会上传 `communication-card-web` artifact（`apps/web/build/dist/wasmJs/productionExecutable` 整包）。
下载解压后**仍然不能双击 index.html** —— 用上面"本地预览"的方式起 server。

---

## 部署到自有服务器

见 `docs/playbooks/web-deploy.md`（Nginx 反代 :server WebSocket + 静态文件）。

---

## 架构速读

| 路径 | 职责 |
|---|---|
| `src/wasmJsMain/kotlin/.../web/Main.kt` | `ComposeViewport` 入口 + 移除 loader |
| `src/wasmJsMain/kotlin/.../web/ui/` | Home / Lobby / Room / Game / Settlement 五个屏幕 |
| `src/wasmJsMain/kotlin/.../web/viewmodel/` | `AppViewModel` 状态机 + sessionJob 生命周期 |
| `src/wasmJsMain/kotlin/.../web/net/` | `WebSocketTransport`（@JsFun 直 interop） + Network/Room/GameSync |
| `src/wasmJsMain/kotlin/.../web/singleplayer/` | 单机模式：包装 `:shared` 的 `GameEngine` |
| `src/wasmJsMain/resources/index.html` | 加载页 + loader（`#loader` 由 wasm 启动后 fade-out 移除） |

依赖 Kotlin 1.9.24 + CMP 1.6.10；不引入 `kotlinx-browser:0.1`（要求 Kotlin 2.0+），
DOM API 通过 `@JsFun` 直接 interop。
