<script setup>
  import { computed, nextTick, onMounted, ref, watch } from 'vue'
  import { RouterLink, useRoute, useRouter } from 'vue-router'
  import { useAuthStore } from '@/stores/auth.js'
  import { useCartStore } from '@/stores/cart.js'

  /**
   * 工作區（workspace）：同一帳號可同時具會員、教練、管理者身分（見 TestDataSeedRunner
   * 的 emp-trainer 帳號），但同一時間通常只在做一件事。全部連結平鋪會讓教練看到 8 個
   * 與工作無關的項目，改成依工作區分組、一次只顯示一組。
   *
   * requires 為進入該工作區所需的角色；null 代表任何人（含未登入）皆可。
   * 這只是選單的可見性，不是安全機制——把關在後端 @PreAuthorize 與路由守衛。
   *
   * AI健康管理（InBody／AI課表／聊天室，皆以 member_id 為主體）歸在會員工作區，
   * 跟「我的訂單」一樣額外標記 requires: 'MEMBER'；學員訊息（教練回覆聊天室訊息）
   * 歸在教練工作區——兩者訪客 or 不具對應角色都看不到，直接輸入網址仍會被
   * router 的 requiresAuth／requiresRole 守衛擋下（見 router/index.js）。
   */
  const WORKSPACES = [
    {
      key: 'member',
      label: '會員',
      requires: null,
      links: [
        { to: '/', label: '首頁' },
        { to: '/courses', label: '課程方案' },
        { to: '/reservation', label: '預約課程' },
        { to: '/group-classes', label: '團體課程' },
        { to: '/products', label: '補給商城' },
        { to: '/subscription', label: '會籍方案' },
        { to: '/orders', label: '我的訂單', requires: 'MEMBER' },
        { to: '/health', label: 'AI健康管理', requires: 'MEMBER' },
      ],
    },
    {
      key: 'trainer',
      label: '教練',
      requires: 'TRAINER',
      links: [
        { to: '/trainer/schedules', label: '教練排班' },
        { to: '/employee/chat', label: '學員訊息' },
      ],
    },
    {
      // 工作區本身只要求 EMPLOYEE：員工申請審核是同儕審核（後端 hasRole('EMPLOYEE')），
      // 一般員工也要看得到。其餘管理功能仍是管理者專屬，各自標 requires: 'ADMIN'。
      key: 'admin',
      label: '管理',
      requires: 'EMPLOYEE',
      links: [
        { to: '/admin/reports', label: '營運統計', requires: 'ADMIN' },
        { to: '/admin/members', label: '會員管理', requires: 'ADMIN' },
        { to: '/admin/group-courses', label: '團課主檔', requires: 'ADMIN' },
        { to: '/admin/group-classes', label: '團課場次', requires: 'ADMIN' },
        { to: '/admin/orders', label: '訂單管理', requires: 'ADMIN' },
        { to: '/admin/refunds', label: '退款處理', requires: 'ADMIN' },
        { to: '/admin/employee-reviews', label: '員工審核' },
      ],
    },
  ]

  const auth = useAuthStore()
  const route = useRoute()
  const router = useRouter()

  // 購物車數量徽章：進站就載入一次，之後由 store 自己在加入／移除時更新
  const cart = useCartStore()
  onMounted(() => cart.loadCart())

  // 數量變多時讓徽章彈一下，作為「加入成功」的視覺回饋。
  // 監看 store 而不是讓各個頁面自己觸發：無論從商城列表、商品明細或日後任何
  // 新增的入口加入，都會自動有回饋，不必每處各接一次。
  const badgeBump = ref(false)
  watch(
    () => cart.itemCount,
    async (next, prev) => {
      // 只在「變多」時播；移除商品也彈會讓刪除動作看起來像加入。
      // prev 為 undefined 代表首次載入既有購物車，不是使用者剛加入的，不播。
      if (prev === undefined || next <= prev) return
      // 先關再開並等一次 DOM 更新，連續加入時動畫才會重新播放而不是被忽略
      badgeBump.value = false
      await nextTick()
      badgeBump.value = true
    },
  )
</script>

<template>
  <aside class="navbar">
    <RouterLink to="/" class="brand">
      <span class="brand-mark">威力</span>
      <span class="brand-sub">健身房管理系統</span>
    </RouterLink>

    <!-- 兩個以上工作區才顯示切換器；只有會員身分的人看不到多餘的 UI -->
    <div v-if="availableWorkspaces.length > 1" class="ws-switch" role="tablist" aria-label="工作區">
      <button
        v-for="ws in availableWorkspaces"
        :key="ws.key"
        type="button"
        role="tab"
        class="ws-tab"
        :class="{ 'ws-tab--active': ws.key === currentWorkspace?.key }"
        :aria-selected="ws.key === currentWorkspace?.key"
        @click="activeKey = ws.key"
      >
        {{ ws.label }}
      </button>
    </div>

    <nav class="nav-links">
      <RouterLink
        v-for="link in visibleLinks"
        :key="link.to"
        :to="link.to"
        class="nav-link"
        :class="{ 'nav-link--active': isLinkActive(link.to) }"
      >
        {{ link.label }}
      </RouterLink>
      <!-- 購物車只在會員工作區出現；要掛數量徽章，無法塞進上面的 v-for -->
      <RouterLink
        v-if="currentWorkspace?.key === 'member' && auth.isMember"
        to="/cart"
        class="nav-link cart-link"
        :class="{ 'nav-link--active': isLinkActive('/cart') }"
      >
        購物車
        <span
          v-if="cart.itemCount > 0"
          class="cart-badge"
          :class="{ 'cart-badge--bump': badgeBump }"
          @animationend="badgeBump = false"
        >{{ cart.itemCount }}</span>
      </RouterLink>
    </nav>


    <div class="nav-auth">
      <!-- mode="out-in"：登入／登出兩種狀態高度不同，同時存在會互相推擠，
           改成舊的先退場、新的才進場 -->
      <Transition name="auth" mode="out-in">
      <div v-if="auth.isLoggedIn" key="in" class="nav-auth__panel">
        <span class="nav-auth__email" :title="auth.email">{{ auth.email }}</span>
        <ul class="nav-auth__roles" aria-label="目前身分" :title="`完整身分：${fullRoleList}`">
          <li v-for="label in roleLabels" :key="label" class="nav-auth__role">{{ label }}</li>
        </ul>
        <button type="button" class="nav-auth__button" @click="onLogout">登出</button>
      </div>
      <RouterLink
        v-else
        key="out"
        class="nav-auth__button nav-auth__button--primary"
        :to="{ name: 'login', query: route.fullPath !== '/' ? { redirect: route.fullPath } : {} }"
      >
        登入
      </RouterLink>
      </Transition>
    </div>

  </aside>
</template>

<style scoped>
  .navbar {
    position: fixed;
    top: 0;
    left: 0;
    bottom: 0;
    width: 240px;
    display: flex;
    flex-direction: column;
    background: #f2e8dc;
    color: var(--text-primary);
    padding: 1.75rem 1.25rem;
    border-right: 2px solid #d5c2ab;
    box-shadow: 6px 0 22px rgba(60, 48, 43, 0.22);
    z-index: 100;
  }

  .brand {
    display: flex;
    flex-direction: column;
    gap: 0.15rem;
    margin-bottom: 2.5rem;
  }
  .brand-mark {
    font-size: 1.6rem;
    font-weight: 900;
    letter-spacing: 0.1em;
    color: var(--primary-dark);
  }
  .brand-sub {
    font-size: 0.78rem;
    letter-spacing: 0.15em;
    color: var(--text-muted);
  }

  .ws-switch {
    display: flex;
    gap: 0.25rem;
    padding: 0.25rem;
    margin-bottom: 1rem;
    border-radius: var(--radius-sm);
    background: #e6d8c6;
  }
  .ws-tab {
    flex: 1;
    padding: 0.4rem 0.2rem;
    border: none;
    border-radius: calc(var(--radius-sm) - 2px);
    background: transparent;
    color: var(--text-secondary);
    font-size: 0.85rem;
    font-weight: 600;
    cursor: pointer;
    transition: var(--transition);
  }
  .ws-tab:hover {
    color: var(--primary-dark);
  }
  .ws-tab--active {
    background: #f2e8dc;
    color: var(--primary-dark);
    box-shadow: 0 1px 3px rgba(60, 48, 43, 0.15);
  }

  .nav-links {
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
    flex: 1;
  }
  .nav-link {
    padding: 0.65rem 0.9rem;
    border-radius: var(--radius-sm);
    font-size: 0.95rem;
    font-weight: 500;
    color: var(--text-secondary);
    transition: var(--transition);
  }
  .nav-link:hover {
    background: #e6d8c6;
    color: var(--primary-dark);
  }
  .cart-link {
    display: flex;
    align-items: center;
    gap: 0.4rem;
  }
  .cart-badge {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 18px;
    height: 18px;
    padding: 0 5px;
    border-radius: 999px;
    background: #e03131;
    color: #fff;
    font-size: 0.72rem;
    font-weight: 700;
  /* 數量增加時彈跳一下。只在「變多」時播——移除商品也重播會讓刪除動作看起來像加入 */
  .cart-badge--bump {
    animation: cart-badge-bump 0.4s cubic-bezier(0.34, 1.56, 0.64, 1);
  }
  @keyframes cart-badge-bump {
    0% { transform: scale(1); }
    40% { transform: scale(1.4); }
    100% { transform: scale(1); }
  }
  @media (prefers-reduced-motion: reduce) {
    .cart-badge--bump { animation: none; }
  }

  .nav-link--active {
    background: var(--accent);
    color: #fff;
    font-weight: 700;
  }

  /* nav-links 佔滿剩餘空間把這塊推到底；原本的 margin-bottom 是為了跟版權字隔開，
     版權字移除後不再需要，留著只會讓登入區浮在離底部一段距離的地方 */
  /* Transition 內只能有一個根節點，已登入的三個元素包成 panel */
  .nav-auth__panel {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }

  /* 登入／登出切換：淡入淡出＋輕微位移，時間短到不拖慢操作感 */
  .auth-enter-active,
  .auth-leave-active {
    transition: opacity 0.22s ease, transform 0.22s ease;
  }
  .auth-enter-from {
    opacity: 0;
    transform: translateY(6px);
  }
  .auth-leave-to {
    opacity: 0;
    transform: translateY(-6px);
  }

  .nav-auth {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }
  .nav-auth__email {
    font-size: 0.78rem;
    color: var(--text-secondary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    text-align: center;
  }
  .nav-auth__roles {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 0.3rem;
  }
  .nav-auth__role {
    padding: 0.1rem 0.55rem;
    border: 1px solid var(--accent);
    border-radius: 999px;
    font-size: 0.72rem;
    font-weight: 600;
    color: var(--accent);
  }
  .nav-auth__button {
    padding: 0.5rem 0.9rem;
    border: 1px solid #d5c2ab;
    border-radius: var(--radius-sm);
    background: transparent;
    color: var(--text-secondary);
    font-size: 0.88rem;
    font-weight: 600;
    text-align: center;
    cursor: pointer;
    transition: var(--transition);
  }
  .nav-auth__button:hover {
    background: #e6d8c6;
    color: var(--primary-dark);
  }
  .nav-auth__button--primary {
    background: var(--accent);
    border-color: var(--accent);
    color: #fff;
  }
  .nav-auth__button--primary:hover {
    background: var(--primary-dark);
    border-color: var(--primary-dark);
    color: #fff;
  }


  @media (max-width: 900px) {
    .navbar {
      right: 0;
      bottom: auto;
      width: 100%;
      height: 60px;
      flex-direction: row;
      align-items: center;
      gap: 1rem;
      padding: 0 1rem;
      border-right: none;
      border-bottom: 2px solid #d5c2ab;
      box-shadow: 0 6px 22px rgba(60, 48, 43, 0.22);
    }
    .brand {
      flex-direction: row;
      align-items: baseline;
      gap: 0.4rem;
      margin-bottom: 0;
    }
    .brand-mark { font-size: 1.2rem; }
    .brand-sub { display: none; }
    .nav-links {
      flex-direction: row;
      overflow-x: auto;
      gap: 0.25rem;
    }
    .nav-link {
      white-space: nowrap;
      padding: 0.4rem 0.7rem;
      font-size: 0.88rem;
    }
    .nav-auth {
      flex-direction: row;
      align-items: center;
      margin-bottom: 0;
      margin-left: auto;
      flex-shrink: 0;
    }
    /* 頂欄寬度有限：隱藏 email，保留較有資訊量的角色徽章與登出鈕 */
    .nav-auth__email { display: none; }
    .nav-auth__roles { flex-wrap: nowrap; }
    .nav-auth__role { white-space: nowrap; }
    .nav-auth__button {
      padding: 0.35rem 0.7rem;
      font-size: 0.82rem;
      white-space: nowrap;
    }
  }
</style>
