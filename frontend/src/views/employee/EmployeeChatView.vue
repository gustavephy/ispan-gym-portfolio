<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import {
  getConversations,
  getEmployeeChatHistory,
  markEmployeeChatRead,
  getEmployeeUnreadCount,
} from '@/api/aihealthchat/chat.js'
import { getMyEmployeeId } from '@/api/aihealthchat/employee.js'
import { createChatSocket } from '@/api/aihealthchat/chatSocket.js'
import { notify } from '@/api/error-bus.js'

const socket = createChatSocket()

const myEmployeeId = ref(null)
const initError = ref('')
const connected = ref(false)

const conversations = ref([])
const loadingConversations = ref(true)
const selectedMemberId = ref(null)

const messages = ref([])
const loadingHistory = ref(false)
const historyError = ref('')
const messageInput = ref('')
const sending = ref(false)
const messageListEl = ref(null)
const totalUnread = ref(0)

let currentSub = null
let pollTimer = null

const selectedConversation = computed(() =>
  conversations.value.find((c) => c.memberId === selectedMemberId.value),
)

const readyRoomId = computed(() => {
  if (!connected.value || !myEmployeeId.value || !selectedMemberId.value) return null
  return `member_${selectedMemberId.value}_employee_${myEmployeeId.value}`
})

const canChat = computed(() => Boolean(readyRoomId.value))

function isMine(msg) {
  return msg.mine ?? !msg.senderIsMember
}

function formatTime(iso) {
  return iso ? iso.replace('T', ' ').slice(5, 16) : ''
}

function scrollToBottom() {
  const el = messageListEl.value
  if (el) el.scrollTop = el.scrollHeight
}

async function refreshConversations() {
  try {
    const { data } = await getConversations()
    conversations.value = data
  } catch {
    // 列表刷新失敗不影響目前開著的對話，靜默忽略即可
  }
}

async function refreshUnread() {
  try {
    const { data } = await getEmployeeUnreadCount()
    totalUnread.value = data.unread
  } catch {
    // 未讀數僅供顯示參考
  }
}

async function onIncoming(msg) {
  messages.value.push(msg)
  nextTick(scrollToBottom)
  if (!isMine(msg)) {
    // 必須等標記已讀完成才重查未讀數與對話列表：三者同時發出的話，查詢常在
    // mark-read 的交易 commit 前就讀到舊值，徽章就會卡在「1」不消失。
    try {
      await markEmployeeChatRead(selectedMemberId.value)
    } catch {
      // 標記失敗不影響訊息顯示，下次進對話或收到新訊息還會再試
    }
    refreshUnread()
  }
  refreshConversations()
}

async function switchConversation(roomId) {
  currentSub?.unsubscribe()
  currentSub = null
  messages.value = []
  historyError.value = ''
  if (!roomId || !selectedMemberId.value) return

  loadingHistory.value = true
  try {
    const { data } = await getEmployeeChatHistory({ memberId: selectedMemberId.value, page: 0, size: 20 })
    messages.value = [...data.content].reverse()
    currentSub = socket.subscribeRoom(roomId, onIncoming)
    await markEmployeeChatRead(selectedMemberId.value)
    refreshUnread()
    refreshConversations()
    await nextTick()
    scrollToBottom()
  } catch (e) {
    historyError.value = e.message
  } finally {
    loadingHistory.value = false
  }
}

watch(readyRoomId, (roomId) => switchConversation(roomId))

function selectConversation(memberId) {
  selectedMemberId.value = memberId
}

function onSend() {
  const content = messageInput.value.trim()
  if (!content || !canChat.value) return
  sending.value = true
  try {
    socket.sendReply({ content, memberId: selectedMemberId.value })
    messageInput.value = ''
  } catch (e) {
    notify('傳送失敗', e.message, 'error')
  } finally {
    sending.value = false
  }
}

onMounted(async () => {
  loadingConversations.value = true
  try {
    const [{ data: me }] = await Promise.all([getMyEmployeeId(), refreshConversations()])
    myEmployeeId.value = me.employeeId
  } catch (e) {
    initError.value = e.message
  } finally {
    loadingConversations.value = false
  }

  try {
    await socket.connect({
      onError: (err) => notify('聊天室錯誤', err.message ?? '發生未預期的錯誤', 'error'),
    })
    connected.value = true
  } catch (e) {
    initError.value = e.message
  }

  refreshUnread()
  // 目前只會即時訂閱正在開啟的那個對話；其他學員的新訊息靠定期刷新列表讓未讀數與預覽保持更新
  pollTimer = setInterval(refreshConversations, 15000)
})

onBeforeUnmount(() => {
  clearInterval(pollTimer)
  currentSub?.unsubscribe()
  socket.disconnect()
})
</script>

<template>
  <div class="page-container employee-chat-page">
    <span class="section-tag">EMPLOYEE</span>
    <h1 class="section-title">學員訊息</h1>
    <p class="section-desc">查看並回覆學員在聊天室「教練」分頁傳來的訊息。</p>

    <p v-if="initError" class="inline-error">{{ initError }}</p>

    <div v-else class="chat-shell card">
      <aside class="conversation-list">
        <div class="conversation-list__header">
          <span>對話列表</span>
          <span v-if="totalUnread > 0" class="unread-badge">{{ totalUnread }}</span>
          <span class="conn-indicator" :class="{ 'conn-indicator--on': connected }">
            {{ connected ? '已連線' : '連線中…' }}
          </span>
        </div>

        <div v-if="loadingConversations" class="spinner"></div>
        <p v-else-if="conversations.length === 0" class="empty-hint">目前還沒有學員傳訊息給你</p>
        <ul v-else class="conversation-items">
          <li
            v-for="c in conversations"
            :key="c.memberId"
            class="conversation-item"
            :class="{ 'conversation-item--active': c.memberId === selectedMemberId }"
            @click="selectConversation(c.memberId)"
          >
            <div class="conversation-item__row">
              <span class="conversation-item__name">{{ c.memberName }}</span>
              <span v-if="c.unreadCount > 0" class="unread-badge">{{ c.unreadCount }}</span>
            </div>
            <p class="conversation-item__preview">{{ c.lastMessage || '（尚無訊息）' }}</p>
            <span class="conversation-item__time">{{ formatTime(c.lastMessageAt) }}</span>
          </li>
        </ul>
      </aside>

      <section class="conversation-thread">
        <template v-if="selectedMemberId">
          <div class="conversation-thread__header">與 {{ selectedConversation?.memberName }} 對話</div>

          <div ref="messageListEl" class="message-list">
            <div v-if="loadingHistory" class="spinner"></div>
            <p v-else-if="historyError" class="inline-error">{{ historyError }}</p>
            <p v-else-if="messages.length === 0" class="empty-hint">還沒有任何訊息</p>
            <div
              v-for="msg in messages"
              :key="msg.id"
              class="message-row"
              :class="{ 'message-row--mine': isMine(msg) }"
            >
              <div class="message-bubble">
                <p class="message-bubble__content">{{ msg.content }}</p>
                <span class="message-bubble__time">{{ formatTime(msg.createdAt) }}</span>
              </div>
            </div>
          </div>

          <div class="chat-input">
            <input
              v-model="messageInput"
              type="text"
              placeholder="輸入回覆…"
              :disabled="!canChat"
              @keydown.enter.prevent="onSend"
            />
            <button type="button" class="btn btn-accent" :disabled="!canChat || sending" @click="onSend">
              傳送
            </button>
          </div>
        </template>
        <p v-else class="empty-hint conversation-thread__placeholder">從左邊選一位學員開始查看對話</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.employee-chat-page {
  padding-bottom: 4rem;
}

.card {
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

/*
 * 高度固定住，不隨訊息數量長高：只有 min-height 的話 grid 會被內容撐開，
 * 訊息一多整頁就越拉越長，輸入框被推到視窗外。改用 height 上限搭配
 * 內層 .message-list 的 overflow-y，讓捲動發生在訊息區內部。
 * 用 vh 而非固定 px，小螢幕不會超出視窗、大螢幕也不浪費空間。
 */
.chat-shell {
  display: grid;
  grid-template-columns: 280px 1fr;
  height: clamp(560px, 72vh, 820px);
  min-height: 0;
  overflow: hidden;
}

.conversation-list {
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  /* grid 項目預設 min-height:auto，不加這行會被內容撐開而不捲動 */
  min-height: 0;
}

.conversation-list__header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 1rem;
  font-weight: 800;
  color: var(--primary-dark);
  border-bottom: 1px solid var(--border);
}

.conn-indicator {
  margin-left: auto;
  font-size: 0.72rem;
  font-weight: 400;
  color: var(--text-muted);
}

.conn-indicator--on::before {
  content: '● ';
  color: #2e7d4f;
}

.conversation-items {
  list-style: none;
}

.conversation-item {
  padding: 0.75rem 1rem;
  border-bottom: 1px solid var(--border);
  cursor: pointer;
  transition: var(--transition);
}

.conversation-item:hover {
  background: var(--bg-soft);
}

.conversation-item--active {
  background: var(--bg-soft);
  border-left: 3px solid var(--accent);
}

.conversation-item__row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.conversation-item__name {
  font-weight: 700;
  color: var(--primary-dark);
}

.conversation-item__preview {
  margin-top: 0.2rem;
  font-size: 0.8rem;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-item__time {
  font-size: 0.7rem;
  color: var(--text-muted);
}

.conversation-thread {
  display: flex;
  flex-direction: column;
  /* 同上：讓內層 .message-list 的 flex:1 + overflow 能真正生效 */
  min-height: 0;
}

.conversation-thread__header {
  padding: 1rem 1.25rem;
  font-weight: 800;
  color: var(--primary-dark);
  border-bottom: 1px solid var(--border);
}

.conversation-thread__placeholder {
  margin: auto;
}

.message-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 0.6rem;
  padding: 1rem 1.25rem;
  min-height: 0;
}

.message-row {
  display: flex;
  max-width: 70%;
}

.message-row--mine {
  align-self: flex-end;
}

.message-bubble {
  background: var(--bg-soft);
  border-radius: var(--radius-sm);
  padding: 0.55rem 0.8rem;
}

.message-row--mine .message-bubble {
  background: var(--accent);
  color: #fff;
}

.message-bubble__content {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 0.92rem;
}

.message-bubble__time {
  display: block;
  margin-top: 0.2rem;
  font-size: 0.68rem;
  opacity: 0.7;
}

.chat-input {
  display: flex;
  gap: 0.6rem;
  padding: 1rem 1.25rem;
  border-top: 1px solid var(--border);
}

.chat-input input {
  flex: 1;
  padding: 0.6rem 0.8rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 0.95rem;
}

.unread-badge {
  padding: 0.1rem 0.5rem;
  border-radius: 999px;
  background: #c0392b;
  color: #fff;
  font-size: 0.72rem;
  font-weight: 700;
}

.empty-hint {
  color: var(--text-muted);
  font-size: 0.9rem;
  padding: 1rem;
}

.inline-error {
  color: #c0392b;
  font-size: 0.9rem;
  padding: 1rem;
}
</style>
