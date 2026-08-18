<script setup>
  import { computed } from 'vue'
  import { RouterLink, useRoute, useRouter } from 'vue-router'
  import { useAuthStore } from '@/stores/auth.js'

  // requires 是「看得到這個連結所需的角色」，未指定＝所有人（含未登入）皆可見。
  // 這裡只管選單的可見性，不是安全機制——真正的把關在後端的 @PreAuthorize，
  // 直接輸入網址仍會被擋（見 EndpointAuthorizationTest）；隱藏連結是為了不讓
  // 使用者點進註定 403 的頁面。
  const navLinks = [
    { to: '/', label: '首頁' },
    { to: '/courses', label: '課程方案' },
    { to: '/reservation', label: '預約課程' },
    { to: '/group-classes', label: '團體課程' },
    { to: '/products', label: '補給商城' },
    { to: '/subscription', label: '會籍方案' },
    { to: '/trainer/schedules', label: '教練排班', requires: 'TRAINER' },
    { to: '/admin/group-courses', label: '後台：團課主檔', requires: 'ADMIN' },
    { to: '/admin/group-classes', label: '後台：團課場次', requires: 'ADMIN' },
  ]

  const auth = useAuthStore()

  const visibleLinks = computed(() =>
    navLinks.filter((link) => !link.requires || auth.roles.includes(link.requires)),
  )
  const route = useRoute()
  const router = useRouter()

  // roles 是疊加式清單（如 ["MEMBER","EMPLOYEE","ADMIN"]），全列出來很吵——
  // 徽章只顯示化簡後的「對外身分」：ADMIN/TRAINER 蘊含 EMPLOYEE（基底角色）故不重複列；
  // 有員工側身分時不再列「會員」。完整清單保留在 title 提示，授權判斷不受影響（store 未動）。
  const ROLE_LABELS = { MEMBER: '會員', EMPLOYEE: '員工', ADMIN: '管理員', TRAINER: '教練' }
  const KNOWN_ROLES = Object.keys(ROLE_LABELS)
  const roleLabels = computed(() => {
    const roles = new Set(auth.roles)
    const labels = []
    if (roles.has('ADMIN')) labels.push('管理員')
    if (roles.has('TRAINER')) labels.push('教練')
    if (labels.length === 0 && roles.has('EMPLOYEE')) labels.push('員工')
    if (labels.length === 0 && roles.has('MEMBER')) labels.push('會員')
    // 未知角色照樣顯示，之後後端新增角色不會無聲消失
    for (const role of roles) {
      if (!KNOWN_ROLES.includes(role)) labels.push(role)
    }
    return labels
  })
  const fullRoleList = computed(() => auth.roles.map((role) => ROLE_LABELS[role] ?? role).join('、'))

  function onLogout() {
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
      >
        {{ link.label }}
      </RouterLink>
    </nav>

    <div class="nav-auth">
      <template v-if="auth.isLoggedIn">
        <span class="nav-auth__email" :title="auth.email">{{ auth.email }}</span>
        <ul class="nav-auth__roles" aria-label="目前身分" :title="`完整身分：${fullRoleList}`">
          <li v-for="label in roleLabels" :key="label" class="nav-auth__role">{{ label }}</li>
        </ul>
        <button type="button" class="nav-auth__button" @click="onLogout">登出</button>
      </template>
      <RouterLink
        v-else
        class="nav-auth__button nav-auth__button--primary"
        :to="{ name: 'login', query: route.fullPath !== '/' ? { redirect: route.fullPath } : {} }"
      >
        登入
      </RouterLink>
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
  .nav-link.router-link-exact-active {
    background: var(--accent);
    color: #fff;
    font-weight: 700;
  }

  /* nav-links 佔滿剩餘空間把這塊推到底；原本的 margin-bottom 是為了跟版權字隔開，
     版權字移除後不再需要，留著只會讓登入區浮在離底部一段距離的地方 */
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
