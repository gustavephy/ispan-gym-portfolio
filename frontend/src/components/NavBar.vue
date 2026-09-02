<script setup>
  import { computed, nextTick, onMounted, ref, watch, onBeforeUnmount } from 'vue'
  import { RouterLink, useRoute, useRouter } from 'vue-router'
  import { useAuthStore } from '@/stores/auth.js'
  import { useCartStore } from '@/stores/cart.js'
  import api from '@/api/axios.instance.js'

  const auth = useAuthStore()
  const frontSidebarOpen = ref(false)
  const displayName = ref('')

  async function fetchDisplayName() {
    if (!auth.isLoggedIn) {
      displayName.value = ''
      return
    }
    try {
      if (auth.isMember) {
        const { data } = await api.get('/api/member/profile')
        displayName.value = (data.lastName || '') + (data.firstName || '') || auth.email
      } else if (auth.isEmployee || auth.isTrainer || auth.isAdmin) {
        const { data } = await api.get('/api/employee/profile')
        displayName.value = (data.lastName || '') + (data.firstName || '') || auth.email
      } else {
        displayName.value = auth.email
      }
    } catch (e) {
      displayName.value = auth.email
    }
  }

  watch(
    () => auth.isLoggedIn,
    (loggedIn) => {
      if (loggedIn) {
        fetchDisplayName()
      } else {
        displayName.value = ''
      }
    },
    { immediate: true }
  )

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
      ],
    },
    {
      key: 'trainer',
      label: '教練',
      requires: 'TRAINER',
      links: [
        { to: '/trainer/schedules', label: '教練排班' },
        { to: '/trainer/profile', label: '教練資料' },
        { to: '/employee/chat', label: '學員訊息' },
      ],
    },
    {
      // 工作區改為要求 ADMIN：防止純教練 (也是 EMPLOYEE) 看到管理工作區
      key: 'admin',
      label: '管理',
      requires: 'ADMIN',
      links: [
        { to: '/admin/reports', label: '營運統計', requires: 'ADMIN' },
        { to: '/admin/analytics', label: '營運分析', requires: 'ADMIN' },
        { to: '/admin/system', label: '系統監控', requires: 'ADMIN' },
        { to: '/admin/members', label: '會員管理', requires: 'ADMIN' },
        { to: '/admin/employees', label: '員工管理', requires: 'ADMIN' },
        { to: '/admin/trainers', label: '教練管理', requires: 'ADMIN' },
        { to: '/admin/trainer-schedules', label: '教練排課查詢', requires: 'ADMIN' },
        { to: '/admin/courses', label: '課程方案', requires: 'ADMIN' },
        { to: '/admin/coupons', label: '優惠券管理', requires: 'ADMIN' },
        { to: '/admin/group-classes', label: '團課場次', requires: 'ADMIN' },
        { to: '/admin/products', label: '商品管理', requires: 'ADMIN' },
        { to: '/admin/inventory', label: '庫存管理', requires: 'ADMIN' },
        { to: '/admin/orders', label: '訂單管理', requires: 'ADMIN' },
        { to: '/admin/refunds', label: '退款處理', requires: 'ADMIN' },
        { to: '/admin/payments', label: '交易紀錄', requires: 'ADMIN' },
        // 員工審核 2026-08-31 起改 ADMIN-only（見 router 與後端 SecurityConfig）
        { to: '/admin/employee-reviews', label: '員工審核', requires: 'ADMIN' },
      ],
    },
  ]

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
        if (auth.isLoggedIn && auth.isTrainer) {
          return false
        }
        return !auth.isLoggedIn || auth.isMember
      }
      return !w.requires || (auth.roles && auth.roles.includes(w.requires))
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

  const menuOpen = ref(false)
  const menuRef = ref(null)
  const toggleMenu = () => { menuOpen.value = !menuOpen.value }

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
      menuOpen.value = false
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
      (link) => !link.requires || (auth.roles && auth.roles.includes(link.requires)),
    ),
  )

  // roles 是疊加式清單（如 ["MEMBER","EMPLOYEE","ADMIN"]），全列出來很吵——
  // 徽章只顯示化簡後的「對外身分」：ADMIN/TRAINER 蘊含 EMPLOYEE（基底角色）故不重複列；
  // 有員工側身分時不再列「會員」。完整清單保留在 title 提示，授權判斷不受影響（store 未動）。
  const ROLE_LABELS = { MEMBER: '會員', EMPLOYEE: '員工', ADMIN: '管理員', TRAINER: '教練' }
  const KNOWN_ROLES = Object.keys(ROLE_LABELS)
  const roleLabels = computed(() => {
    const roles = new Set(auth.roles || [])
    const labels = []
    // 對外徽章使用簡稱；完整身分提示仍顯示「管理員」
    if (roles.has('ADMIN')) labels.push('管理')
    if (roles.has('TRAINER')) labels.push('教練')
    if (labels.length === 0 && roles.has('EMPLOYEE')) labels.push('員工')
    if (labels.length === 0 && roles.has('MEMBER')) labels.push('會員')
    // 未知角色照樣顯示，之後後端新增角色不會無聲消失
    for (const role of roles) {
      if (!KNOWN_ROLES.includes(role)) labels.push(role)
    }
    return labels
  })
  const fullRoleList = computed(() => (auth.roles || []).map((role) => ROLE_LABELS[role] ?? role).join('、'))
  const isPureMember = computed(() => auth.isMember && !auth.isTrainer && !auth.isEmployee && !auth.isAdmin)

  const ADMIN_GROUPS = [
    {
      key: 'reports',
      label: '營運數據',
      links: [
        { to: '/admin/reports', label: '營運統計', requires: 'ADMIN' },
        { to: '/admin/analytics', label: '營運分析', requires: 'ADMIN' },
        { to: '/admin/system', label: '系統監控', requires: 'ADMIN' }
      ]
    },
    {
      key: 'hr_member',
      label: '人事與會員',
      links: [
        { to: '/admin/members', label: '會員管理', requires: 'ADMIN' },
        { to: '/admin/employees', label: '員工管理', requires: 'ADMIN' },
        { to: '/admin/trainers', label: '教練管理', requires: 'ADMIN' },
        // 員工審核 2026-08-31 起改 ADMIN-only（見 router 與後端 SecurityConfig）
        { to: '/admin/employee-reviews', label: '員工審核', requires: 'ADMIN' },
        { to: '/admin/payments', label: '交易紀錄', requires: 'ADMIN' }
      ]
    },
    {
      key: 'course',
      label: '課程與排課',
      links: [
        { to: '/admin/courses', label: '課程方案', requires: 'ADMIN' },
        { to: '/admin/group-classes', label: '團課場次', requires: 'ADMIN' },
        { to: '/admin/trainer-schedules', label: '教練排課查詢', requires: 'ADMIN' }
      ]
    },
    {
      key: 'store',
      label: '商城與訂單',
      links: [
        { to: '/admin/products', label: '商品管理', requires: 'ADMIN' },
        { to: '/admin/inventory', label: '庫存管理', requires: 'ADMIN' },
        { to: '/admin/orders', label: '訂單管理', requires: 'ADMIN' },
        { to: '/admin/refunds', label: '退款處理', requires: 'ADMIN' },
        { to: '/admin/coupons', label: '優惠券管理', requires: 'ADMIN' }
      ]
    }
  ]

  const openGroups = ref({
    reports: false,
    hr_member: false,
    course: false,
    store: false
  })

  function toggleGroup(key) {
    openGroups.value[key] = !openGroups.value[key]
  }

  // 監聽路由變化，自動展開包含當前選中連結的分類分組
  watch(
    () => route.path,
    (path) => {
      ADMIN_GROUPS.forEach((g) => {
        if (g.links.some((l) => path === l.to || path.startsWith(l.to + '/'))) {
          openGroups.value[g.key] = true
        }
      })
    },
    { immediate: true }
  )

  const visibleAdminGroups = computed(() => {
    return ADMIN_GROUPS.map((g) => {
      const links = g.links.filter(
        (link) => !link.requires || (auth.roles && auth.roles.includes(link.requires))
      )
      return { ...g, links }
    }).filter((g) => g.links.length > 0)
  })



  function handleClickOutside(e) {
    if (menuRef.value && !menuRef.value.contains(e.target)) {
      menuOpen.value = false
    }
  }

  onMounted(() => {
    document.addEventListener('click', handleClickOutside)
    window.addEventListener('profile-updated', fetchDisplayName)
  })

  onBeforeUnmount(() => {
    document.removeEventListener('click', handleClickOutside)
    window.removeEventListener('profile-updated', fetchDisplayName)
  })

  function onLogout() {
    auth.logout()
    // 目前所有頁面皆可匿名瀏覽，登出後留在原頁即可；回首頁讓狀態變化更明顯
    router.push('/')
  }
</script>

<template>
  <div>
    <!-- Mobile Top Header Bar -->
    <header class="mobile-top-bar">
      <button class="btn-toggle-sidebar-front" @click="frontSidebarOpen = !frontSidebarOpen">☰</button>
      <RouterLink to="/" class="nav-logo-mobile">
        <span class="brand-mark">威力</span>
      </RouterLink>
      <div class="mobile-top-actions">
        <RouterLink
          v-if="isPureMember"
          to="/cart"
          class="cart-btn-mobile"
          title="購物車"
        >
          <span class="cart-icon">🛒</span>
          <span v-if="cart.itemCount > 0" class="cart-badge">{{ cart.itemCount }}</span>
        </RouterLink>
      </div>
    </header>

    <!-- Overlay Background when Sidebar is open on narrow screens -->
    <div v-if="frontSidebarOpen" class="sidebar-overlay-front" @click="frontSidebarOpen = false"></div>

    <!-- Left Sidebar Navbar -->
    <aside class="navbar" :class="{ 'sidebar-open': frontSidebarOpen }">
      <div class="nav-inner">
        <!-- Logo 區塊 -->
        <RouterLink to="/" class="brand" @click="frontSidebarOpen = false">
          <span class="brand-mark">威力</span>
          <span class="brand-sub">健身房管理系統</span>
        </RouterLink>

        <!-- 兩個以上工作區才顯示切換器 -->
        <div v-if="availableWorkspaces.length > 1" class="ws-switch" role="tablist" aria-label="工作區">
          <button
            v-for="ws in availableWorkspaces"
            :key="ws.key"
            type="button"
            role="tab"
            class="ws-tab"
            :class="{ 'ws-tab--active': ws.key === currentWorkspace?.key }"
            :aria-selected="ws.key === currentWorkspace?.key"
            @click="activeKey = ws.key; frontSidebarOpen = false"
            :title="ws.label"
          >
            {{ ws.label }}
          </button>
        </div>

        <nav class="nav-links">
          <!-- 管理後台專屬的摺疊分組選單 -->
          <template v-if="currentWorkspace?.key === 'admin'">
            <div v-for="group in visibleAdminGroups" :key="group.key" class="menu-group">
              <!-- 單一連結時扁平顯示 -->
              <template v-if="group.links.length === 1">
                <RouterLink
                  :to="group.links[0].to"
                  class="nav-link"
                  :class="{ 'nav-link--active': isLinkActive(group.links[0].to) }"
                  @click="frontSidebarOpen = false"
                >
                  {{ group.label }}
                </RouterLink>
              </template>
              
              <!-- 複數連結時維持摺疊分組 -->
              <template v-else>
                <button type="button" class="menu-group-header" @click="toggleGroup(group.key)">
                  <span class="group-title">{{ group.label }}</span>
                  <span class="group-arrow" :class="{ 'group-arrow--open': openGroups[group.key] }">
                    <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
                      <polyline points="6 9 12 15 18 9"></polyline>
                    </svg>
                  </span>
                </button>
                <div v-show="openGroups[group.key]" class="menu-group-content">
                  <RouterLink
                    v-for="link in group.links"
                    :key="link.to"
                    :to="link.to"
                    class="nav-link nav-link--sub"
                    :class="{ 'nav-link--active': isLinkActive(link.to) }"
                    @click="frontSidebarOpen = false"
                  >
                    {{ link.label }}
                  </RouterLink>
                </div>
              </template>
            </div>
          </template>

          <!-- 其它工作區（會員、教練）維持一般扁平選單 -->
          <template v-else>
            <RouterLink
              v-for="link in visibleLinks"
              :key="link.to"
              :to="link.to"
              class="nav-link"
              :class="{ 'nav-link--active': isLinkActive(link.to) }"
              @click="frontSidebarOpen = false"
            >
              {{ link.label }}
            </RouterLink>
          </template>
        </nav>
      </div>
      <!-- nav-auth 移至 navbar 直屬，見下方 -->
      <!-- nav-auth 移至 navbar 直屬，不再在 nav-inner 內 -->
      <div class="nav-auth">
        <!-- 我的購物車只在登入為會員時出現 -->
        <RouterLink
          v-if="activeKey === 'member' && isPureMember"
          to="/cart"
          class="nav-link cart-link desktop-only"
          :class="{ 'nav-link--active': isLinkActive('/cart') }"
          @click="frontSidebarOpen = false"
        >
          我的購物車
          <span
            v-if="cart.itemCount > 0"
            class="cart-badge"
            :class="{ 'cart-badge--bump': badgeBump }"
            @animationend="badgeBump = false"
          >{{ cart.itemCount }}</span>
        </RouterLink>

        <Transition name="auth" mode="out-in">
        <div v-if="auth.isLoggedIn" key="in" class="nav-auth__panel">
          <div class="user-menu" @click.stop="toggleMenu" ref="menuRef">
            <div class="user-info-row">
              <span class="user-name" :title="auth.email">{{ displayName || auth.email }}</span>
            </div>
            <svg class="chevron-icon" :class="{ 'chevron-icon--open': menuOpen }" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="6 9 12 15 18 9"/></svg>

            <!-- Dropup list -->
            <Transition name="dropup">
            <div v-if="menuOpen" class="dropdown">
              <template v-if="activeKey === 'member' && isPureMember">
                <RouterLink to="/member/profile" class="dropdown-item" @click="frontSidebarOpen = false">會員資料</RouterLink>
                <RouterLink to="/member/courses" class="dropdown-item" @click="frontSidebarOpen = false">我的課程</RouterLink>
                <RouterLink to="/reservation?tab=bookings" class="dropdown-item" @click="frontSidebarOpen = false">我的預約</RouterLink>
                <RouterLink to="/orders" class="dropdown-item" @click="frontSidebarOpen = false">我的訂單</RouterLink>
                <RouterLink to="/coupons" class="dropdown-item" @click="frontSidebarOpen = false">我的優惠券</RouterLink>
                <RouterLink to="/health" class="dropdown-item" @click="frontSidebarOpen = false">我的AI健康管理</RouterLink>
              </template>
              <template v-else>
                <RouterLink to="/employee/profile" class="dropdown-item" @click="frontSidebarOpen = false">基本資料</RouterLink>
              </template>
              <RouterLink to="/account/security" class="dropdown-item" @click="frontSidebarOpen = false">帳號設定</RouterLink>
              <div class="dropdown-divider"></div>
              <button type="button" class="dropdown-item dropdown-item--danger" @click="onLogout(); frontSidebarOpen = false">登出</button>
            </div>
            </Transition>
          </div>
          <ul class="nav-auth__roles" aria-label="目前身分" :title="`完整身分：${fullRoleList}`">
            <li v-for="label in roleLabels" :key="label" class="nav-auth__role">{{ label }}</li>
          </ul>
        </div>
        <div v-else key="out" class="nav-auth__panel">
          <RouterLink
            class="nav-auth__button nav-auth__button--primary"
            :to="{ name: 'login', query: route.fullPath !== '/' ? { redirect: route.fullPath } : {} }"
            @click="frontSidebarOpen = false"
          >
            登入
          </RouterLink>
          <RouterLink
            class="nav-auth__button"
            to="/member-register"
            @click="frontSidebarOpen = false"
          >
            註冊
          </RouterLink>
        </div>
        </Transition>
      </div>
    </aside>
  </div>
</template>

<style scoped>
/* Mobile Top Header Bar */
  .mobile-top-bar {
    display: none;
  }

  .sidebar-overlay-front {
    position: fixed;
    top: 0; left: 0; right: 0; bottom: 0;
    background: rgba(0, 0, 0, 0.4);
    z-index: 999;
    backdrop-filter: blur(2px);
  }

  /* Left Sidebar Navbar */
  .navbar {
    position: fixed;
    top: 0;
    left: 0;
    bottom: 0;
    width: 240px;
    display: flex;
    flex-direction: column;
    background: var(--bg-section);
    color: var(--text-primary);
    padding: 1.5rem 1rem;
    border-right: 1px solid var(--border);
    box-shadow: 2px 0 12px rgba(0, 0, 0, 0.03);
    z-index: 1000;
    overflow-y: auto;
    scrollbar-width: thin;
  }

  .nav-divider {
    height: 1px;
    background: var(--border);
    margin: 0.5rem 0;
    flex-shrink: 0;
  }

  .nav-inner {
    display: flex;
    flex-direction: column;
    flex: 1;
    /* 移除 min-height: 0 與 overflow: hidden 讓整支側欄或內部容器在高度不足時可以被撐開滑動，
       或是搭配 flex-shrink: 0 防止子組件擠壓 */
    gap: 1rem;
  }

  .brand {
    display: flex;
    flex-direction: column;
    gap: 0.15rem;
    padding: 0.25rem;
    flex-shrink: 0;
  }
  .brand-mark {
    font-size: 1.6rem;
    font-weight: 900;
    letter-spacing: 0.1em;
    color: var(--primary-dark);
    white-space: nowrap;
  }
  .brand-sub {
    font-size: 0.78rem;
    letter-spacing: 0.15em;
    color: var(--text-muted);
    white-space: nowrap;
  }

  .ws-switch {
    display: flex;
    gap: 0.25rem;
    padding: 0.25rem;
    border-radius: var(--radius-sm);
    background: var(--bg-soft);
    flex-shrink: 0;
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
    background: var(--bg-section);
    color: var(--primary-dark);
    box-shadow: 0 1px 3px rgba(60, 48, 43, 0.1);
  }

  .nav-links {
    display: flex;
    flex-direction: column;
    gap: 0.3rem;
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    overflow-x: hidden;
    scrollbar-width: thin;
    scrollbar-color: var(--border) transparent;
    scrollbar-gutter: stable; /* 永遠預留滑軌空間，按鈕寬度不因滑軌出現而改變 */
    overscroll-behavior: contain;
    padding-bottom: 0.5rem;
    margin-bottom: 0.75rem;
  }

  .nav-links::-webkit-scrollbar {
    width: 6px;
  }
  .nav-links::-webkit-scrollbar-thumb {
    background: var(--border);
    border-radius: 999px;
  }
  .nav-links::-webkit-scrollbar-track {
    background: transparent;
  }

  /* 後台摺疊分組選單 */
  .menu-group {
    margin-bottom: 0.2rem;
    border-radius: var(--radius-sm);
    overflow: hidden;
    flex-shrink: 0; /* 禁止被其他分組壓縮 */
  }
  .menu-group-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    width: 100%;
    padding: 0.35rem 0.5rem;
    background: var(--bg-soft);
    border: 1px solid var(--border);
    cursor: pointer;
    text-align: left;
    border-radius: var(--radius-sm);
    transition: var(--transition);
  }
  .menu-group-header:hover {
    background: var(--bg-section);
    border-color: var(--accent);
  }
  .group-title {
    font-size: 0.88rem;
    font-weight: 700;
    letter-spacing: 0.02em;
    color: var(--text-secondary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .group-arrow {
    display: flex;
    align-items: center;
    justify-content: center;
    transition: transform 0.2s ease;
    color: var(--text-muted);
  }
  .group-arrow--open {
    transform: rotate(180deg);
    color: var(--primary-dark);
  }
  .menu-group-content {
    display: flex;
    flex-direction: column;
    gap: 0.2rem;
    padding-left: 0.85rem;
    margin-top: 0.2rem;
    border-left: 1px solid var(--border);
    margin-left: 1.35rem;
    margin-bottom: 0.3rem;
  }
  .nav-link--sub {
    padding: 0.22rem 0.45rem;
    font-size: 0.8rem;
    border-radius: var(--radius-sm);
    color: var(--text-secondary);
    border: 1px solid var(--border);
    background: var(--bg-body);
    transition: var(--transition);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    display: block;
  }
  .nav-link--sub:hover,
  .nav-link--sub.nav-link--active {
    color: var(--primary-dark);
    background: var(--bg-soft);
    border-color: var(--accent);
    font-weight: 600;
  }

  .nav-link {
    padding: 0.55rem 0.75rem;
    border-radius: var(--radius-sm);
    color: var(--text-secondary);
    font-size: 0.875rem;
    font-weight: 500;
    transition: var(--transition);
    text-decoration: none;
    display: block;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    border-left: 3px solid transparent;
    letter-spacing: 0.01em;
    flex-shrink: 0; /* 防止垂直空間壓縮按鈕高度 */
  }
  .nav-link:hover {
    color: var(--text-primary);
    background: var(--border);
    border-left-color: var(--accent);
    font-weight: 600;
  }
  .nav-link--active {
    color: #ffffff;
    background: var(--primary-dark);
    border-left-color: var(--accent);
    font-weight: 700;
  }
  .cart-link {
    position: relative;
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
    margin-left: 0.4rem;
  }

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

  .nav-auth {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
    flex-shrink: 0;
    border-top: 1px solid var(--border);
    padding-top: 1rem;
    margin-top: 0.75rem;
  }

  .nav-auth__panel {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }

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
    border: 1px solid var(--border);
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
    background: var(--bg-soft);
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

  /* 使用者選單 */
  .user-menu {
    position: relative;
    display: flex; 
    align-items: center; 
    justify-content: space-between;
    cursor: pointer;
    padding: 0.6rem 0.9rem;
    border-radius: var(--radius-sm);
    border: 1px solid var(--border);
    background: var(--bg-soft);
    user-select: none;
  }
  .user-info-row {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    min-width: 0;
  }
  .user-name {
    font-size: 0.85rem;
    color: var(--text-primary);
    font-weight: 500;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .chevron-icon {
    transition: transform 0.2s;
    flex-shrink: 0;
  }
  .chevron-icon--open {
    transform: rotate(180deg);
  }
  
  /* 上拉選單 */
  .dropdown {
    position: absolute;
    bottom: calc(100% + 8px);
    left: 0;
    right: 0;
    background: var(--bg-section);
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    padding: 0.4rem;
    box-shadow: 0 4px 16px rgba(0,0,0,0.08);
    z-index: 10;
  }
  .dropup-enter-active { transition: opacity 0.15s ease, transform 0.15s ease; }
  .dropup-leave-active { transition: opacity 0.1s ease, transform 0.1s ease; }
  .dropup-enter-from { opacity: 0; transform: translateY(6px); }
  .dropup-leave-to   { opacity: 0; transform: translateY(6px); }
  .dropdown-item {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    padding: 0.5rem 0.8rem;
    border-radius: var(--radius-sm);
    color: var(--text-secondary);
    font-size: 0.85rem;
    text-decoration: none;
    background: none;
    border: none;
    width: 100%;
    cursor: pointer;
    transition: var(--transition);
    text-align: left;
    font-weight: 500;
  }
  .dropdown-item:hover {
    background: var(--bg-soft);
    color: var(--primary-dark);
  }
  .dropdown-item--danger {
    color: #e03131;
  }
  .dropdown-item--danger:hover {
    background: #ffe3e3;
    color: #c01e1e;
  }
  .dropdown-divider {
    height: 1px;
    background: var(--border);
    margin: 0.4rem 0;
  }

  @media (max-width: 900px) {
    .mobile-top-bar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      position: fixed;
      top: 0; left: 0; right: 0;
      height: 60px;
      background: rgba(255, 255, 255, 0.95);
      backdrop-filter: blur(12px);
      border-bottom: 1px solid var(--border);
      padding: 0 1rem;
      z-index: 998;
      box-shadow: 0 2px 10px rgba(0,0,0,0.04);
    }
    .btn-toggle-sidebar-front {
      font-size: 1.5rem;
      background: transparent;
      border: none;
      cursor: pointer;
      color: var(--text-primary);
    }
    .nav-logo-mobile {
      display: flex;
      align-items: center;
      gap: 0.4rem;
      text-decoration: none;
      font-weight: 800;
      color: var(--primary-dark);
      font-size: 0.95rem;
    }
    .mobile-top-actions {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }
    .cart-btn-mobile {
      position: relative;
      display: flex; align-items: center; justify-content: center;
      width: 36px; height: 36px;
      color: var(--text-secondary);
    }
    .cart-icon {
      font-size: 1.25rem;
    }

    .desktop-only {
      display: none !important;
    }

    /* Floating drawer sidebar */
    .navbar {
      position: fixed;
      top: 0;
      left: 0;
      bottom: 0;
      width: 240px;
      height: 100vh;
      transform: translateX(-100%);
      transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
      background: rgba(250, 246, 240, 0.95);
      backdrop-filter: blur(10px);
      z-index: 1000;
      padding: 2rem 1rem 1rem;
      border-right: 1px solid rgba(0, 0, 0, 0.08);
      box-shadow: 4px 0 25px rgba(0, 0, 0, 0.15);
    }
    .navbar.sidebar-open {
      transform: translateX(0);
    }
  }
</style>
