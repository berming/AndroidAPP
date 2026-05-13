<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { alertsApi, type Alert } from '../api/alerts'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'

const alerts = ref<Alert[]>([])
const loading = ref(false)
const showAcked = ref(false)
let pollTimer: number | undefined

async function refresh() {
  loading.value = true
  try {
    alerts.value = showAcked.value
      ? await alertsApi.listRecent(200)
      : await alertsApi.listUnacked(200)
  } finally {
    loading.value = false
  }
}

async function ackOne(a: Alert) {
  try {
    await alertsApi.ack(a.id)
    ElMessage.success(`#${a.id} 已 ack`)
    refresh()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.error || 'ack 失败')
  }
}

onMounted(() => {
  refresh()
  pollTimer = window.setInterval(refresh, 10_000)
})
onBeforeUnmount(() => { if (pollTimer) window.clearInterval(pollTimer) })

function severityType(s: string): string {
  switch (s) {
    case 'ERROR': return 'danger'
    case 'WARN': return 'warning'
    case 'INFO': return 'info'
    default: return ''
  }
}
</script>

<template>
  <div>
    <div class="page-head">
      <h2 class="page-title">异常告警（{{ alerts.length }}）</h2>
      <div>
        <el-switch v-model="showAcked" active-text="包含已 ack" @change="refresh" style="margin-right: 12px;" />
        <el-button @click="refresh" :loading="loading">刷新</el-button>
      </div>
    </div>
    <el-card>
      <el-table :data="alerts" v-loading="loading && alerts.length === 0" stripe>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column label="级别" width="100">
          <template #default="{ row }">
            <el-tag :type="severityType(row.severity)" size="small">{{ row.severity }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="rule" label="规则" width="200" />
        <el-table-column prop="message" label="详情" />
        <el-table-column prop="roomId" label="房间" width="120">
          <template #default="{ row }">{{ row.roomId ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="创建" width="160">
          <template #default="{ row }">{{ dayjs(row.createdAt).format('MM-DD HH:mm:ss') }}</template>
        </el-table-column>
        <el-table-column label="状态" width="160">
          <template #default="{ row }">
            <span v-if="!row.ackedAt" style="color: #f56c6c;">未处理</span>
            <span v-else style="color: #67c23a;">
              {{ row.ackedBy }} · {{ dayjs(row.ackedAt).format('MM-DD HH:mm') }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button v-if="!row.ackedAt" size="small" type="primary" link @click="ackOne(row)">
              ack
            </el-button>
            <span v-else style="color: #c0c4cc;">—</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="showAcked ? '暂无告警' : '没有未处理告警 👍'" />
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.page-title { margin: 0; color: #303133; font-weight: 600; font-size: 18px; }
</style>
