<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { alertsApi } from '../api/alerts'
import AlertWatcher from '../components/AlertWatcher.vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const router = useRouter()
const auth = useAuthStore()

const unackedCount = ref(0)
let pollTimer: number | undefined

async function refreshAlertBadge() {
  try {
    const list = await alertsApi.listUnacked(200)
    unackedCount.value = list.length
  } catch (_e) { /* 401 拦截器接管 */ }
}

onMounted(() => {
  refreshAlertBadge()
  pollTimer = window.setInterval(refreshAlertBadge, 30_000)
})
onBeforeUnmount(() => { if (pollTimer) window.clearInterval(pollTimer) })

const menu = computed(() => [
  { path: '/dashboard', label: '数据总览', icon: 'DataLine' },
  { path: '/rooms', label: '房间监控', icon: 'House' },
  { path: '/players', label: '玩家列表', icon: 'User' },
  { path: '/sessions', label: '当前会话', icon: 'Connection' },
  { path: '/games', label: '历史游戏', icon: 'Clock' },
  { path: '/alerts', label: '异常告警', icon: 'Bell', badge: unackedCount.value },
])

async function handleLogout() {
  await auth.logout()
  router.push('/login')
}

async function handleChangePassword() {
  try {
    const old = await ElMessageBox.prompt('请输入当前密码', '修改密码', {
      inputType: 'password',
      confirmButtonText: '下一步',
      cancelButtonText: '取消',
    })
    const next = await ElMessageBox.prompt('请输入新密码（至少 8 位）', '修改密码', {
      inputType: 'password',
      confirmButtonText: '提交',
      cancelButtonText: '取消',
      inputValidator: (v) => (v && v.length >= 8) ? true : '密码至少 8 位',
    })
    await auth.changePassword(old.value, next.value)
    ElMessage.success('密码已修改')
  } catch (e: any) {
    if (e !== 'cancel' && e?.response) {
      ElMessage.error(e.response?.data?.error || '修改密码失败')
    }
  }
}
</script>

<template>
  <el-container class="admin-shell">
    <el-aside width="220px" class="admin-aside">
      <div class="admin-brand">沟通牌 · Admin</div>
      <el-menu
        :default-active="$route.path"
        router
        background-color="#001428"
        text-color="rgba(255,255,255,0.85)"
        active-text-color="#409EFF"
      >
        <el-menu-item
          v-for="m in menu"
          :key="m.path"
          :index="m.path"
        >
          <el-icon><component :is="m.icon" /></el-icon>
          <span>{{ m.label }}</span>
          <el-badge
            v-if="m.badge && m.badge > 0"
            :value="m.badge"
            class="menu-badge"
            type="danger"
          />
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="admin-header">
        <div class="admin-header-spacer" />
        <el-dropdown>
          <span class="admin-user">
            <el-icon><user /></el-icon>
            {{ auth.user?.username }} ({{ auth.user?.role }})
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="handleChangePassword">修改密码</el-dropdown-item>
              <el-dropdown-item divided @click="handleLogout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>

      <el-main class="admin-main">
        <router-view />
      </el-main>
    </el-container>

    <alert-watcher @ack-changed="refreshAlertBadge" />
  </el-container>
</template>

<style scoped>
.admin-shell { height: 100vh; }
.admin-aside { background: #001428; color: #fff; overflow: hidden; }
.admin-brand {
  padding: 16px 20px;
  font-size: 18px;
  font-weight: 600;
  color: #fff;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}
.admin-header {
  display: flex; align-items: center; justify-content: flex-end;
  background: #fff; border-bottom: 1px solid #ebeef5; padding: 0 24px;
}
.admin-header-spacer { flex: 1; }
.admin-user { display: inline-flex; gap: 6px; align-items: center; cursor: pointer; color: #303133; }
.admin-main { background: #f5f7fa; padding: 24px; overflow: auto; }
.menu-badge { margin-left: auto; }
</style>
