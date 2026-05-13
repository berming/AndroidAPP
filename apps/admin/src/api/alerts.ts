import { http } from './http'

export interface Alert {
  id: number
  rule: string
  severity: string
  roomId: string | null
  playerIdMasked: string | null
  message: string
  payload: Record<string, string> | null
  createdAt: string
  ackedAt: string | null
  ackedBy: string | null
}

export const alertsApi = {
  async listUnacked(limit = 100): Promise<Alert[]> {
    return (await http.get<Alert[]>('/admin/api/alerts', { params: { unack: true, limit } })).data
  },
  async listRecent(limit = 100): Promise<Alert[]> {
    return (await http.get<Alert[]>('/admin/api/alerts', { params: { limit } })).data
  },
  async ack(id: number): Promise<void> {
    await http.post(`/admin/api/alerts/${id}/ack`)
  },
}
