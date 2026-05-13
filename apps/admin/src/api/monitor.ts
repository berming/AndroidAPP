import { http } from './http'

export interface Overview {
  serverStartedAt: string
  uptimeSeconds: number
  totalRooms: number
  roomsByStatus: Record<string, number>
  totalPlayers: number
  humanPlayersConnected: number
  humanPlayersDisconnected: number
  aiPlayers: number
  aiSubstituted: number
  totalSessions: number
  jvmHeapUsedMb: number
  jvmHeapMaxMb: number
  todayGameCount: number
  totalGameCount: number
}

export interface RoomSummary {
  roomId: string
  roomCode: string
  roomName: string
  status: string
  playerCount: number
  maxPlayers: number
  humanCount: number
  aiCount: number
  phase: string | null
  currentPlayerIndex: number | null
  teamAScore: number
  teamBScore: number
  version: number
  serverAiDelayMs: number
}

export interface RoomPlayerDetail {
  playerIdMasked: string
  name: string
  seatIndex: number
  team: string
  isAI: boolean
  isAISubstitute: boolean
  isConnected: boolean
  isReady: boolean
  takeoverAiDelayMs: number
  handSize: number
  collectedScore: number
}

export interface SerializedCard { rank: string; suit: string; deckIndex: number }
export interface SerializedCardGroup {
  cards: SerializedCard[]
  type: string
  primaryRank: string
}

export interface RoomDetail {
  summary: RoomSummary
  players: RoomPlayerDetail[]
  lastPlayedGroup: SerializedCardGroup | null
  lastPlayerId: number | null
  consecutivePasses: number
  currentRoundScore: number
  finishOrder: number[]
}

export interface PlayerSummary {
  playerIdMasked: string
  name: string
  roomId: string
  roomCode: string
  seatIndex: number
  team: string
  isAI: boolean
  isAISubstitute: boolean
  isConnected: boolean
  handSize: number
  collectedScore: number
}

export interface SessionInfo {
  sessionIdMasked: string
  roomId: string | null
  playerName: string
  seatIndex: number
  connectedAtIso: string
  connectedSeconds: number
}

export const monitorApi = {
  async overview(): Promise<Overview> {
    return (await http.get<Overview>('/admin/api/overview')).data
  },
  async rooms(): Promise<RoomSummary[]> {
    return (await http.get<RoomSummary[]>('/admin/api/rooms')).data
  },
  async room(id: string): Promise<RoomDetail> {
    return (await http.get<RoomDetail>(`/admin/api/rooms/${encodeURIComponent(id)}`)).data
  },
  async players(): Promise<PlayerSummary[]> {
    return (await http.get<PlayerSummary[]>('/admin/api/players')).data
  },
  async sessions(): Promise<SessionInfo[]> {
    return (await http.get<SessionInfo[]>('/admin/api/sessions')).data
  },
}
