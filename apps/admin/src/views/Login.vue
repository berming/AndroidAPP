<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { ElMessage } from 'element-plus'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const form = reactive({
  username: '',
  password: '',
})
const submitting = ref(false)
// Vite SPA 一定有 window；之前的 typeof guard 是死代码（pr-reviewer PR #61 nit）
const serverHost = ref(window.location.host)

async function handleSubmit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请填写用户名和密码')
    return
  }
  submitting.value = true
  try {
    await auth.login(form.username, form.password)
    const redirect = (route.query.redirect as string) || '/dashboard'
    router.push(redirect)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.error || '用户名或密码错误')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-shell">
    <el-card class="login-card">
      <div class="login-title">沟通牌 · Admin 登录</div>
      <el-form :model="form" @submit.prevent="handleSubmit">
        <el-form-item label="用户名" label-position="top">
          <el-input v-model="form.username" placeholder="root" autofocus />
        </el-form-item>
        <el-form-item label="密码" label-position="top">
          <el-input v-model="form.password" type="password" show-password placeholder="••••••••" @keyup.enter="handleSubmit" />
        </el-form-item>
        <el-button type="primary" :loading="submitting" style="width: 100%" @click="handleSubmit">
          登录
        </el-button>
      </el-form>
      <div class="login-foot">
        服务器：<code>{{ serverHost }}</code>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.login-shell {
  height: 100vh;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #001428 0%, #234567 100%);
}
.login-card { width: 380px; padding: 8px 4px; }
.login-title {
  text-align: center; font-size: 20px; font-weight: 600;
  margin-bottom: 24px; color: #303133;
}
.login-foot { margin-top: 16px; font-size: 12px; color: #909399; text-align: center; }
</style>
