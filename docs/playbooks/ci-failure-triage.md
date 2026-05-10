# Playbook · Loop D — CI 失败排错

> 这是 Loop B 的"前置过滤器"：CI 失败时先按本流程**分类**，再决定走
> 哪条修复路径。乱试乱猜是 PR 反复红的最大成本。

参考：`.github/workflows/android-ci.yml`（CI 步骤定义）+
`docs/regressions.md`（历史失败模式）+ Loop B（真 Bug 走那个流程）。

---

## 0. 第一时间做的事

不是"立刻修"。是**先看**：

```
mcp__github__pull_request_read method=get_check_runs ...
```

或在网页上打开 GitHub Actions 页面。**找到具体哪一步红**——CI 是
顺序执行的，早期步骤红会跳过后期步骤，"看似 5 个红"实际只是 1 个。

---

## 1. 按失败步骤路由

### Step 1: `Set up JDK 17` 失败

罕见。通常是 GitHub runner 镜像问题。重试 → 不行就等 24 小时。**不要
改你的代码。**

### Step 2: `Cache Gradle Dependencies` 失败

软失败，不会阻塞后续。忽略。

### Step 3: `Run shared module tests (commonTest)` —— `:shared:jvmTest` 失败

最常见。这是 Loop B 的入口。

```bash
/test-fast
```

本地复现失败 → 进 Loop B（先把现象转成红色测试，反推根因）。
本地不复现 → 多半是测试本身依赖外部状态（时间 / 文件系统 / 随机数）。
检查测试是否用了 `Random.Default` / `System.currentTimeMillis()` 这类
unstable 源。

### Step 4: `Run server unit tests` —— `./gradlew :server:test` 失败

```bash
./gradlew :server:test --console=plain
```

本地复现 → Loop B。
本地不复现 → 检查 server build.gradle 的依赖版本是否真的与 CI 环境
一致（snapshot 依赖会漂）。

### Step 5: `Detekt static analysis` 失败

PR-H2 之前是 `continue-on-error: true`，**不会真的阻塞**——但屏幕上
会标红，看起来像失败。先确认是不是这个步骤被 fail，但 job-level
status 仍是 success。

PR-H2 之后会变成 hard gate。本地复现：

```bash
./gradlew detekt
```

报告在 `*/build/reports/detekt/detekt.html`。

### Step 6: `Build Debug APK with Gradle` —— `:apps:android:assembleDebug` 失败

通常是：

- Kotlin 编译错误（看 `error:` 行，定位文件）
- KMP 变体不匹配（jvmTarget 不一致 / 缺 publishLibraryVariants）
- 资源 / manifest 合并冲突
- Gradle 配置错误（apply false 漏了某个 plugin / 版本不兼容）

**已知历史模式**（PR #35 上踩过）：

| 错误片段 | 根因 | 修复 |
|---------|------|------|
| "Inconsistent JVM-target compatibility" | :shared androidTarget jvmTarget 与 :apps 不一致 | 对齐到 1.8 |
| "Could not resolve org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3" 在 wasmJs target | 1.7.x 没有 wasmJs 变体 | 升 1.8.1 |
| "Unresolved reference: assertNull" 在 commonTest | JUnit→kotlin.test 转换漏导入 | 显式 `import kotlin.test.assertNull` |

如果是新错误：

1. 沙箱不能跑 gradle，去 GitHub Actions 网页页面看完整 log
2. 把错误前后 30 行喂给 Opus 4.7
3. 问"这是 KMP / AGP / Kotlin 版本兼容问题，还是真 Bug？"
4. 兼容问题 → 改 build.gradle.kts；真 Bug → 进 Loop B

### Step 7: `Build Web (Wasm-JS) bundle` —— `:apps:web:wasmJsBrowserDistribution` 失败

类似 Step 6。常见模式：

- Compose Multiplatform 版本与 Kotlin 版本不匹配
- `kotlinx-browser` 等 wasmJs-only 依赖未声明
- `@JsFun` interop 写错（`kotlin.js.JsFun` 包导入）

### Step 8: `Upload artifacts` 失败 / `if-no-files-found: warn`

**软警告**——artifact 缺失通常是因为前一步根本没构建成功（连 APK 都
没生成）。修前面的步骤就解决了。

---

## 2. 第二次 CI 还是红

**不要硬怼**。同一个 commit message + 类似改动连续两次失败，意味着：

- 你修的不是真根因（症状层修了，结构层还在）
- 你的本地状态与 CI 状态有差异（环境 / 缓存 / 工具链）

行动：

1. **开新会话**（`/clear`），把"两次失败的 CI 错误"喂给空白上下文的 Opus
2. 让它给一个**与之前完全不同**的假设
3. 走 Loop B 把假设转成测试

引用 dev_summary.md 8.2：

> 同一 commit 反复改：3 次以上还红，原因往往不在你本地——是 CI 缓存
> 污染或工具链版本漂移。**重置缓存 + bump runner 镜像**比继续改代码
> 更有效。

---

## 3. CI 红但本地都绿的诊断顺序

按从快到慢排：

| 假设 | 验证方式 |
|------|---------|
| Gradle 缓存污染 | CI workflow 加一次 `--rerun-tasks` 跑通确认 |
| Java/Kotlin 版本漂移 | 看 CI log 中 `gradle --version` 输出 |
| 测试依赖 system property（时区/随机数） | 在测试里 `println()` 出来对比 |
| OS 差异（CI 是 Linux，本地是 macOS） | 路径分隔符 / 行尾 / locale |
| 随机种子 | 测试里 `Random()` 没有显式 seed |
| 隐式依赖运行顺序 | 加 `@TestMethodOrder(OrderAnnotation::class)` |

---

## 4. 紧急 push（**只在生产事故**）

如果是 main 已经红、阻塞别人，且你已经定位到一个**最小修复**但 CI
环境配置问题让你测不了——可以：

```bash
EMERGENCY_PUSH=1 git push
```

绕开 pre-push hook。但**必须在 PR 描述里说明**为什么紧急 + 后续如何
补测试。Codex Bot 会留意 EMERGENCY_PUSH 关键词并提示。

---

## 5. 沙箱 / 无登录环境读不到 CI 日志？把 gradle 输出 exfil 到 PR 评论

**前提**：从 Claude Code 沙箱（或任何无 GitHub 登录态的环境）通过 WebFetch
抓 GitHub Actions job 页只能看到 step 名 + "Process completed with exit
code 1"——真正的 gradle stderr 在登录后才能看。

**结果**：调试 CI 失败时容易陷入"猜→改→push→等 4 分钟→还是猜"循环。
PR #35 早期连烧 3 个推测性 commit 才意识到这是结构性问题。

**解决方案**：在 CI workflow 里加一个 `if: failure()` 步骤，把 gradle
log 的关键片段贴回 PR 作为评论。评论是公开的 JSON，沙箱可通过
`mcp__github__pull_request_read method=get_comments` 直接读到。

```yaml
- name: Build Debug APK with Gradle
  # 加 --info --stacktrace + tee 到日志文件
  run: |
    set -o pipefail
    ./gradlew :apps:android:assembleDebug --info --stacktrace 2>&1 \
      | tee assembleDebug.log

- name: Surface assembleDebug error on failure
  if: failure() && hashFiles('assembleDebug.log') != ''
  env:
    GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
    PR_NUMBER: ${{ github.event.pull_request.number }}
  run: |
    {
      echo "## :apps:android:assembleDebug 失败"
      echo
      echo "### Kotlin compiler errors（grep）"
      echo '```'
      grep -nE "^e: |^w: |^error:|FAILURE:|^> Task .* FAILED" assembleDebug.log \
        | head -80 || echo "(no Kotlin error lines)"
      echo '```'
      echo
      echo "### 末 300 行 gradle 输出"
      echo '```'
      tail -300 assembleDebug.log
      echo '```'
    } > comment.md
    gh pr comment "$PR_NUMBER" --body-file comment.md
```

**关键设计**：

- **head + grep + tail 三段输出**。Gradle 错误的 "What went wrong:" /
  "Could not resolve" 经常在日志开头几百行；Kotlin compile 的 `e:`
  在中段；exception stack trace 在末尾。三段都抓，下一次失败立刻定位。
- **`if: failure() && hashFiles(...) != ''` 双重护栏**。前一步成功时
  log 文件不存在，避免空评论刷屏。
- **`permissions: pull-requests: write`** 放在 workflow 顶层；GITHUB_TOKEN
  默认权限不够发评论。

**PR #35 campaign 验证**：投入这一个 commit 后，剩下所有的 CI 失败都
能"从评论里抓到错→一次精准修复"，从盲改 3 次降到平均每个错误 1 次
push 解决。该模式是 CLAUDE.md 第三章 TDD 反向流的工具链版本——
**反馈通道比反馈速度更值得投资**。

---

## 6. 写回 `docs/regressions.md`?

CI 失败本身大多不是产品 Bug——除非：

- 它揭示了一个真 Bug（比如 detekt 抓到 `!!` 实际是个空指针潜在风险）
- 它揭示了协议漂移（约束 1/4 的实例）

那种情况进 Loop B 处理，记进 regressions.md。
**纯工具链失败**（gradle 配置错、版本号写错）不入 regressions.md，
但可以加到本 playbook 第 1 节的"已知历史模式"表格。

---

## 反模式

- **看到红就盲改 build.gradle.kts**：80% 的时候改完更红
- **本地不复现就强 push**："CI 还会再跑一次"是错觉，每次失败都浪费 2-3 分钟
- **同一会话连续修 5 次**：你已经被偏见困住了，开新会话
- **跳过"先看具体哪一步红"**：CI 红 ≠ 5 个步骤都红，找到第一个就够了
