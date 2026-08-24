import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { useAuthStore } from '@/stores/auth.js'

/**
 * 聊天室 WebSocket 封裝（docs/API-REFERENCE.md 14）。
 *
 * 認證方式跟一般 REST 不一樣：JWT 不是放在 HTTP header，而是 STOMP CONNECT frame
 * 的原生 header（connectHeaders），後端 StompAuthChannelInterceptor 只在 CONNECT
 * 階段驗證一次。SockJS 的 HTTP 交握本身（/ws-chat/info 等）不需要帶 token。
 *
 * 用法：
 *   const socket = createChatSocket()
 *   await socket.connect()
 *   const sub = socket.subscribeRoom(roomId, (msg) => ...)
 *   socket.sendToMember({ content, counterpartType, employeeId })
 *   sub.unsubscribe()
 *   socket.disconnect()
 */
export function createChatSocket() {
  const auth = useAuthStore()
  let client = null
  let errorSub = null

  function connect({ onError } = {}) {
    return new Promise((resolve, reject) => {
      client = new Client({
        webSocketFactory: () => new SockJS('/ws-chat'),
        connectHeaders: { Authorization: `Bearer ${auth.token}` },
        reconnectDelay: 4000,
        onConnect: () => {
          // 個人錯誤頻道（無權訂閱房間、業務例外等），格式為 ApiErrorResponse
          errorSub = client.subscribe('/user/queue/errors', (frame) => {
            if (typeof onError === 'function') {
              try {
                onError(JSON.parse(frame.body))
              } catch {
                onError({ message: frame.body })
              }
            }
          })
          resolve()
        },
        onStompError: (frame) => reject(new Error(frame.headers?.message || 'STOMP 連線發生錯誤')),
        onWebSocketError: () => reject(new Error('聊天室連線失敗，請確認網路狀態')),
      })
      client.activate()
    })
  }

  function subscribeRoom(roomId, onMessage) {
    if (!client?.connected) throw new Error('聊天室尚未連線')
    return client.subscribe(`/topic/room/${roomId}`, (frame) => {
      onMessage(JSON.parse(frame.body))
    })
  }

  /** 學員發起／回覆訊息；counterpartType: 'AI' | 'EMPLOYEE'，EMPLOYEE 時 employeeId 必填 */
  function sendToMember({ content, counterpartType, employeeId }) {
    if (!client?.connected) throw new Error('聊天室尚未連線')
    client.publish({
      destination: '/app/chat.send',
      body: JSON.stringify({ content, counterpartType, employeeId: employeeId ?? null }),
    })
  }

  /** 員工（教練／管理員）回覆指定學員 */
  function sendReply({ content, memberId }) {
    if (!client?.connected) throw new Error('聊天室尚未連線')
    client.publish({
      destination: '/app/chat.reply',
      body: JSON.stringify({ content, memberId }),
    })
  }

  async function disconnect() {
    errorSub?.unsubscribe()
    if (client) {
      await client.deactivate()
      client = null
    }
  }

  return { connect, subscribeRoom, sendToMember, sendReply, disconnect }
}
