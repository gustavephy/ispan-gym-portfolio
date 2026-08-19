<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { getChatHistory, markChatRead, getUnreadCount, recommendCourse } from '@/api/aihealthchat/chat.js'
import { getTrainers } from '@/api/aihealthchat/trainer.js'
import { getMyMemberId } from '@/api/aihealthchat/member.js'
import { createChatSocket } from '@/api/aihealthchat/chatSocket.js'
import { notify } from '@/api/error-bus.js'

const socket = createChatSocket()

const myMemberId = ref(null)
const trainers = ref([])
const initError = ref('')
const connected = ref(false)

const activeTab = ref('ai') // 'ai' | 'employee'
const selectedEmployeeId = ref(null)

const messages = ref([])
const loadingHistory = ref(false)
const historyError = ref('')
const unreadCount = ref(0)
const recommending = ref(false)
const messageInput = ref('')
const sending = ref(false)
const messageListEl = ref(null)

let currentSub = null

const currentCounterpartType = computed(() => (activeTab.value === 'ai' ? 'AI' : 'EMPLOYEE'))
const currentEmployeeId = computed(() => (activeTab.value === 'employee' ? selectedEmployeeId.value : null))

const currentRoomId = computed(() => {
  if (!myMemberId.value) return null
  if (activeTab.value === 'ai') return `member_${myMemberId.value}_ai`
  if (!selectedEmployeeId.value) return null
  return `member_${myMemberId.value}_employee_${selectedEmployeeId.value}`
})

// 只有「已連上 WebSocket」且「房間已能算出來」時才真的訂閱／載入歷史；
// 兩個條件中任一個之後才成立都要觸發，用一個 computed 合併成單一訊號最省事。
const readyRoomId = computed(() => (connected.value ? currentRoomId.value : null))

const canChat = computed(() => Boolean(readyRoomId.value))

function isMine(msg) {
  return msg.mine ?? msg.senderIsMember
}

function formatTime(iso) {
  return iso ? iso.replace('T', ' ').slice(5, 16) : ''
}

function scrollToBottom() {
  const el = messageListEl.value
  if (el) el.scrollTop = el.scrollHeight
}

async function refreshUnread() {
  try {
    const { data } = await getUnreadCount()
    unreadCount.value = data.unread
  } catch {
    // 未讀數僅供顯示參考，失敗不影響聊天室主功能
  }
}

async function onIncoming(msg) {
  messages.value.push(msg)
  nextTick(scrollToBottom)
  if (!isMine(msg)) {
    // 必須等標記已讀完成才重查未讀數：兩者同時發出的話，unread-count 常在
    // mark-read 的交易 commit 前就讀到舊值，畫面就會卡在「1 則未讀」不消失。
    try {
      await markChatRead({
        counterpartType: currentCounterpartType.value,
        employeeId: currentEmployeeId.value,
      })
    } catch {
      // 標記失敗不影響訊息顯示，下次進房間或收到新訊息還會再試
    }
    refreshUnread()
  }
}

async function switchRoom(roomId) {
  currentSub?.unsubscribe()
  currentSub = null
  messages.value = []
  historyError.value = ''
  if (!roomId) return

  loadingHistory.value = true
  try {
    const { data } = await getChatHistory({
      counterpartType: currentCounterpartType.value,
      employeeId: currentEmployeeId.value,
      page: 0,
      size: 20,
    })
    messages.value = [...data.content].reverse()
    currentSub = socket.subscribeRoom(roomId, onIncoming)
    await markChatRead({
      counterpartType: currentCounterpartType.value,
      employeeId: currentEmployeeId.value,
    })
    refreshUnread()
    await nextTick()
    scrollToBottom()
  } catch (e) {
    historyError.value = e.message
  } finally {
    loadingHistory.value = false
  }
}

watch(readyRoomId, (roomId) => switchRoom(roomId), { immediate: true })

function onSend() {
  const content = messageInput.value.trim()
  if (!content || !canChat.value) return
  sending.value = true
  try {
    socket.sendToMember({
      content,
      counterpartType: currentCounterpartType.value,
      employeeId: currentEmployeeId.value,
    })
    messageInput.value = ''
  } catch (e) {
    notify('傳送失敗', e.message, 'error')
  } finally {
    sending.value = false
  }
}

async function onRecommendCourse() {
  recommending.value = true
  try {
    await recommendCourse()
    notify('已送出', 'AI 已收到課程推薦請求，稍後會在對話中回覆', 'info')
  } catch (e) {
    notify('請求失敗', e.message, 'error')
  } finally {
    recommending.value = false
  }
}

function changeTrainer() {
  selectedEmployeeId.value = null
}

onMounted(async () => {
  try {
    const [{ data: me }, { data: trainerList }] = await Promise.all([getMyMemberId(), getTrainers()])
    myMemberId.value = me.memberId
    trainers.value = trainerList
  } catch (e) {
    initError.value = e.message
  }

  try {
    await socket.connect({
      onError: (err) => notify('聊天室錯誤', err.message ?? '發生未預期的錯誤', 'error'),
    })
    connected.value = true
  } catch (e) {
    initError.value = e.message
  }
})

onBeforeUnmount(() => {
  currentSub?.unsubscribe()
  socket.disconnect()
})
</script>

<template>
  <div class="chat-panel">
    <p v-if="initError" class="inline-error">{{ initError }}</p>

    <div v-else class="chat-shell card">
      <div class="chat-tabs">
        <button
          type="button"
          class="chat-tabs__btn"
          :class="{ 'chat-tabs__btn--active': activeTab === 'ai' }"
          @click="activeTab = 'ai'"
        >
          AI 顧問
        </button>
        <button
          type="button"
          class="chat-tabs__btn"
          :class="{ 'chat-tabs__btn--active': activeTab === 'employee' }"
          @click="activeTab = 'employee'"
        >
          教練
        </button>
        <span v-if="unreadCount > 0" class="unread-badge">{{ unreadCount }} 則未讀</span>
        <span class="conn-indicator" :class="{ 'conn-indicator--on': connected }">
          {{ connected ? '已連線' : '連線中…' }}
        </span>
      </div>

      <div v-if="activeTab === 'employee' && !selectedEmployeeId" class="trainer-picker">
        <p v-if="trainers.length === 0" class="empty-hint">目前尚無教練資料</p>
        <template v-else>
          <label class="trainer-picker__field">
            <span>選擇要對話的教練</span>
            <select v-model="selectedEmployeeId">
              <option disabled :value="null">請選擇教練</option>
              <option v-for="t in trainers" :key="t.trainerId" :value="t.employeeId">
                {{ t.displayName }}（{{ t.specialty }}）
              </option>
            </select>
          </label>
        </template>
      </div>

      <template v-else>
        <div v-if="activeTab === 'employee'" class="chat-target">
          與
          <strong>{{ trainers.find((t) => t.employeeId === selectedEmployeeId)?.displayName }}</strong>
          教練對話
          <button type="button" class="link-btn" @click="changeTrainer">換一位教練</button>
        </div>

        <div ref="messageListEl" class="message-list">
          <div v-if="loadingHistory" class="spinner"></div>
          <p v-else-if="historyError" class="inline-error">{{ historyError }}</p>
          <p v-else-if="messages.length === 0" class="empty-hint">還沒有任何訊息，開始對話吧！</p>
          <div
            v-for="msg in messages"
            :key="msg.id"
            class="message-row"
            :class="{ 'message-row--mine': isMine(msg) }"
          >
            <span v-if="!isMine(msg)" class="message-row__label">{{ msg.counterpartLabel }}</span>
            <div class="message-bubble">
              <p class="message-bubble__content">{{ msg.content }}</p>
              <span class="message-bubble__time">{{ formatTime(msg.createdAt) }}</span>
            </div>
          </div>
        </div>

        <div class="chat-input">
          <button
            v-if="activeTab === 'ai'"
            type="button"
            class="btn btn-outline chat-input__recommend"
            :disabled="recommending"
            @click="onRecommendCourse"
          >
            {{ recommending ? '請求中…' : '推薦本館課程' }}
          </button>
          <input
            v-model="messageInput"
            type="text"
            placeholder="輸入訊息…"
            :disabled="!canChat"
            @keydown.enter.prevent="onSend"
          />
          <button type="button" class="btn btn-accent" :disabled="!canChat || sending" @click="onSend">
            傳送
          </button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.chat-panel {
  display: flex;
  flex-direction: column;
}

.card {
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 1.5rem;
}

.chat-shell {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  min-height: 520px;
}

.chat-tabs {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  border-bottom: 1px solid var(--border);
  padding-bottom: 0.75rem;
}

.chat-tabs__btn {
  padding: 0.5rem 1rem;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--bg-soft);
  color: var(--text-secondary);
  font-weight: 700;
  cursor: pointer;
  transition: var(--transition);
}

.chat-tabs__btn--active {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}

.unread-badge {
  padding: 0.15rem 0.6rem;
  border-radius: 999px;
  background: #c0392b;
  color: #fff;
  font-size: 0.78rem;
  font-weight: 700;
}

.conn-indicator {
  margin-left: auto;
  font-size: 0.78rem;
  color: var(--text-muted);
}

.conn-indicator--on::before {
  content: '● ';
  color: #2e7d4f;
}

.trainer-picker {
  padding: 1.5rem 0;
}

.trainer-picker__field {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
  font-size: 0.9rem;
  color: var(--text-secondary);
  max-width: 320px;
}

.trainer-picker__field select {
  padding: 0.55rem 0.7rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}

.chat-target {
  font-size: 0.88rem;
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.message-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 0.6rem;
  padding: 0.5rem 0.25rem;
  max-height: 360px;
}

.message-row {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  max-width: 75%;
}

.message-row--mine {
  align-self: flex-end;
  align-items: flex-end;
}

.message-row__label {
  font-size: 0.72rem;
  color: var(--text-muted);
  margin-bottom: 0.15rem;
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
  align-items: center;
}

.chat-input input {
  flex: 1;
  padding: 0.6rem 0.8rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 0.95rem;
}

.chat-input__recommend {
  white-space: nowrap;
}

.empty-hint {
  color: var(--text-muted);
  font-size: 0.9rem;
}

.inline-error {
  color: #c0392b;
  font-size: 0.9rem;
}

.link-btn {
  border: none;
  background: none;
  color: var(--accent);
  font-weight: 600;
  cursor: pointer;
  padding: 0;
  font-size: 0.85rem;
}

.link-btn:hover {
  text-decoration: underline;
}
</style>
