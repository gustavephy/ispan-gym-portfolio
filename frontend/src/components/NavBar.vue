<script setup>
  import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
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
   * 歸在教練工作區——兩者訪客或不具對應角色都看不到，直接輸入網址仍會被
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
        { to: '/member/courses', label: '我的課程', requires: 'MEMBER' },
        { to: '/member/profile', label: '會員資料', requires: 'MEMBER' },
        { to: '/coupons', label: '優惠券', requires: 'MEMBER' },
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
        { to: '/admin/trainer-schedules', label: '教練排課查詢', requires: 'ADMIN' },
        { to: '/admin/courses', label: '課程方案', requires: 'ADMIN' },
        { to: '/admin/coupons', label: '優惠券管理', requires: 'ADMIN' },
        { to: '/admin/group-classes', label: '團課場次', requires: 'ADMIN' },
        { to: '/admin/products', label: '商品管理', requires: 'ADMIN' },
        { to: '/admin/inventory', label: '庫存管理', requires: 'ADMIN' },
        { to: '/admin/orders', label: '訂單管理', requires: 'ADMIN' },
        { to: '/admin/refunds', label: '退款處理', requires: 'ADMIN' },
        { to: '/admin/payments', label: '交易紀錄', requires: 'ADMIN' },
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

  /**
   * 目前身分進得去的工作區；只有一個時不顯示切換器。
   *
   * 會員工作區的 requires 是 null（前台商城要讓未登入訪客也能瀏覽），但「已登入卻沒有
   * 會員身分」的帳號（純員工／純管理員）不該看到它——那些頁面多半需要 member_id，
   * 點進去只會拿到錯誤。因此已登入時額外要求 MEMBER 角色，未登入則維持開放。
   */
  const availableWorkspaces = computed(() =>
    WORKSPACES.filter((w) => {
      if (w.key === 'member') {
        return !auth.isLoggedIn || auth.isMember
      }
      return !w.requires || auth.roles.includes(w.requires)
    }),
  )

  /**
   * 目前工作區：以「網址落在哪個工作區」為準，而不是另外存一份選中狀態——
   * 使用者從外部連結或重新整理進來時，選單才不會停在別的工作區。
   */
  const activeKey = ref('member')

  /**
   * 連結是否該亮起：自己比對路徑前綴，不用 router-link-exact-active——
   * exact 在巢狀路由下永遠不亮（/health 進頁即轉址 /health/inbody，路徑不再「完全等於」/health；
   * 商品內頁 /products/5 也同理）。也不能用 router-link-active：首頁「/」是萬用前綴，會永遠亮著。
   * 比對時補上「/」邊界，避免 /course 誤亮 /courses 這種同前綴不同路由。
   */
  function isLinkActive(to) {
    if (to === '/') return route.path === '/'
    return route.path === to || route.path.startsWith(to + '/')
  }
  const currentWorkspace = computed(
    () => availableWorkspaces.value.find((w) => w.key === activeKey.value) ?? availableWorkspaces.value[0],
  )

  // 網址對不到任何工作區時（例如首頁 '/'）退回第一個可用的，而不是寫死 'member'——
  // 純員工／純管理員沒有會員工作區，退回去會讓 currentWorkspace 落空、選單整個空白
  function workspaceOf(path) {
    const hit = WORKSPACES.find((w) => w.links.some((l) => l.to !== '/' && path.startsWith(l.to)))
    if (hit && availableWorkspaces.value.some((w) => w.key === hit.key)) {
      return hit.key
    }
    return availableWorkspaces.value[0]?.key ?? 'member'
  }

  watch(
    () => route.path,
    (path) => {
      activeKey.value = workspaceOf(path)
    },
    { immediate: true },
  )

  // 角色變動（登入／登出）後，若當時停在已不可用的工作區會變成空選單，拉回第一個可用的。
  // 不寫死 'member'：純員工／純管理員沒有會員工作區，第一個可用的會是教練或管理
  watch(
    () => auth.roles,
    () => {
      if (!availableWorkspaces.value.some((w) => w.key === activeKey.value)) {
        activeKey.value = availableWorkspaces.value[0]?.key ?? 'member'
      }
    },
    { deep: true },
  )

  const visibleLinks = computed(() =>
    (currentWorkspace.value?.links ?? []).filter(
      (link) => !link.requires || auth.roles.includes(link.requires),
    ),
  )

  // 下拉選單切換的是既有 workspace，不修改 auth.roles。admin workspace 同時承接一般員工
  // 與管理員功能，因此只有 ADMIN 時顯示「管理」，其餘 EMPLOYEE 顯示「員工」。
  const ROLE_LABELS = { MEMBER: '會員', EMPLOYEE: '員工', ADMIN: '管理員', TRAINER: '教練' }
  const fullRoleList = computed(() => auth.roles.map((role) => ROLE_LABELS[role] ?? role).join('、'))
  const workspaceOptions = computed(() =>
    availableWorkspaces.value.map((workspace) => ({
      key: workspace.key,
      label: workspace.key === 'admin' ? (auth.isAdmin ? '管理' : '員工') : workspace.label,
    })),
  )
  const currentWorkspaceLabel = computed(
    () => workspaceOptions.value.find((workspace) => workspace.key === currentWorkspace.value?.key)?.label ?? '身分',
  )

  const identityMenuOpen = ref(false)
  const accountMenuOpen = ref(false)
  const identityDropdown = ref(null)
  const accountDropdown = ref(null)

  function closeMenus() {
    identityMenuOpen.value = false
    accountMenuOpen.value = false
  }

  function toggleIdentityMenu() {
    if (workspaceOptions.value.length <= 1) return
    identityMenuOpen.value = !identityMenuOpen.value
    accountMenuOpen.value = false
  }

  function toggleAccountMenu() {
    accountMenuOpen.value = !accountMenuOpen.value
    identityMenuOpen.value = false
  }

  function selectWorkspace(key) {
    activeKey.value = key
    closeMenus()
  }

  function onDocumentPointerDown(event) {
    if (identityDropdown.value?.contains(event.target) || accountDropdown.value?.contains(event.target)) return
    closeMenus()
  }

  function onDocumentKeydown(event) {
    if (event.key === 'Escape') closeMenus()
  }

  onMounted(() => {
    document.addEventListener('pointerdown', onDocumentPointerDown)
    document.addEventListener('keydown', onDocumentKeydown)
  })

  onBeforeUnmount(() => {
    document.removeEventListener('pointerdown', onDocumentPointerDown)
    document.removeEventListener('keydown', onDocumentKeydown)
  })

  function onLogout() {
    closeMenus()
    auth.logout()
    // 目前所有頁面皆可匿名瀏覽，登出後留在原頁即可；回首頁讓狀態變化更明顯
    router.push('/')
  }
</script>

<template>
  <aside class="navbar">
    <RouterLink to="/" class="brand">
      <span class="brand-mark">威力</span>
      <span class="brand-sub">健身房管理系統</span>
    </RouterLink>

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
        <div ref="identityDropdown" class="nav-auth__dropdown nav-auth__dropdown--identity">
          <button
            v-if="workspaceOptions.length > 1"
            type="button"
            class="nav-auth__role nav-auth__role--button"
            aria-haspopup="menu"
            :aria-expanded="identityMenuOpen"
            :title="`完整身分：${fullRoleList}`"
            @click="toggleIdentityMenu"
          >
            {{ currentWorkspaceLabel }}
          </button>
          <span v-else class="nav-auth__role" :title="`完整身分：${fullRoleList}`">
            {{ currentWorkspaceLabel }}
          </span>
          <div v-if="identityMenuOpen" class="nav-auth__menu" role="menu" aria-label="切換身分">
            <button
              v-for="workspace in workspaceOptions"
              :key="workspace.key"
              type="button"
              role="menuitemradio"
              class="nav-auth__menu-item nav-auth__menu-item--identity"
              :class="{ 'nav-auth__menu-item--active': workspace.key === currentWorkspace?.key }"
              :aria-checked="workspace.key === currentWorkspace?.key"
              @click="selectWorkspace(workspace.key)"
            >
              {{ workspace.label }}
            </button>
          </div>
        </div>
        <div ref="accountDropdown" class="nav-auth__dropdown nav-auth__dropdown--account">
          <button
            type="button"
            class="nav-auth__button nav-auth__button--account"
            :class="{ 'nav-link--active': isLinkActive('/account/security') }"
            aria-haspopup="menu"
            :aria-expanded="accountMenuOpen"
            @click="toggleAccountMenu"
          >
            帳號
          </button>
          <div v-if="accountMenuOpen" class="nav-auth__menu" role="menu" aria-label="帳號功能">
            <RouterLink
              to="/account/security"
              role="menuitem"
              class="nav-auth__menu-item"
              :class="{ 'nav-auth__menu-item--active': isLinkActive('/account/security') }"
              @click="closeMenus"
            >
              帳號設定
            </RouterLink>
            <button type="button" role="menuitem" class="nav-auth__menu-item nav-auth__menu-item--logout" @click="onLogout">
              登出
            </button>
          </div>
        </div>
      </div>
      <div v-else key="out" class="nav-auth__panel">
        <RouterLink
          class="nav-auth__button nav-auth__button--primary"
          :to="{ name: 'login', query: route.fullPath !== '/' ? { redirect: route.fullPath } : {} }"
        >
          登入
        </RouterLink>
        <RouterLink class="nav-auth__button" to="/member-register">
          註冊
        </RouterLink>
      </div>
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
    /* 側欄是 fixed 且上下撐滿，內容超過視窗高度時預設會被裁掉又無法捲動——
       多重身分帳號的工作區連結較多，1280x720 這類矮視窗下底部的帳號區塊會完全點不到 */
    overflow-y: auto;
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
  }

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
  .nav-auth__role {
    display: block;
    width: fit-content;
    margin: 0 auto;
    padding: 0.1rem 0.55rem;
    border: 1px solid var(--accent);
    border-radius: 999px;
    background: transparent;
    /* 跟登入／註冊／帳號共用的 .nav-auth__button 字級對齊，三者不再各自一套大小 */
    font-size: 0.82rem;
    font-weight: 600;
    color: var(--accent);
  }
  .nav-auth__role--button {
    cursor: pointer;
    transition: var(--transition);
  }
  .nav-auth__role--button:hover,
  .nav-auth__role--button[aria-expanded='true'] {
    background: rgba(176, 132, 79, 0.12);
  }
  .nav-auth__dropdown {
    position: relative;
  }
  .nav-auth__dropdown--account,
  .nav-auth__button--account {
    width: 100%;
  }
  .nav-auth__menu {
    position: absolute;
    left: 50%;
    right: auto;
    transform: translateX(-50%);
    bottom: calc(100% + 0.4rem);
    z-index: 20;
    display: flex;
    flex-direction: column;
    gap: 0.15rem;
    /* 寬度貼合內容——原本寫死 9rem，比「會員」「登出」這類兩三個字的項目寬了三倍以上 */
    width: fit-content;
    padding: 0.35rem;
    border: 1px solid #d5c2ab;
    border-radius: var(--radius-sm);
    background: #f8f1e8;
    box-shadow: 0 8px 24px rgba(60, 48, 43, 0.2);
  }
  .nav-auth__menu-item {
    display: flex;
    align-items: center;
    gap: 0.35rem;
    width: 100%;
    min-height: 2.25rem;
    padding: 0.4rem 0.55rem;
    border: none;
    border-radius: calc(var(--radius-sm) - 2px);
    background: transparent;
    color: var(--text-secondary);
    font: inherit;
    font-size: 0.82rem;
    font-weight: 600;
    text-align: center;
    justify-content: center;
    white-space: nowrap;
    cursor: pointer;
    transition: var(--transition);
  }
  .nav-auth__menu-item:hover,
  .nav-auth__menu-item--active {
    background: #e6d8c6;
    color: var(--primary-dark);
  }
  .nav-auth__menu-item:hover {
    transform: scale(1.08);
  }
  /* 拿掉勾勾符號，改用文字顏色本身標示目前選到哪一個——沿用原本勾勾的顏色。
     :not(:hover) 讓滑鼠移過去時仍走上面 :hover 那條的 primary-dark，不會被這裡蓋掉 */
  .nav-auth__menu-item--identity.nav-auth__menu-item--active:not(:hover) {
    color: var(--accent);
  }
  .nav-auth__menu-item--logout {
    color: #9b4338;
  }
  /* 放大只是 hover 的位移效果，登出本來的紅色文字不該被上面的 :hover 覆蓋掉 */
  .nav-auth__menu-item--logout:hover {
    color: #9b4338;
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
    /* 頂欄寬度有限：隱藏 email，保留身分與帳號兩個精簡選單 */
    .nav-auth__email { display: none; }
    .nav-auth__role { white-space: nowrap; }
    /* nav-auth__panel 桌面版是直向堆疊，手機版改成橫向並排。 */
    .nav-auth__panel {
      flex-direction: row;
      align-items: center;
      gap: 0.5rem;
    }
    .nav-auth__button {
      padding: 0.35rem 0.7rem;
      font-size: 0.82rem;
      white-space: nowrap;
    }
    .nav-auth__button--account {
      width: auto;
    }
    .nav-auth__menu {
      top: calc(100% + 0.4rem);
      right: 0;
      bottom: auto;
      left: auto;
      width: fit-content;
      transform: none;
    }
  }
</style>
