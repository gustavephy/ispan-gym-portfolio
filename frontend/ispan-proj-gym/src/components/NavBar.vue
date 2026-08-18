<script setup>
  import { onMounted } from 'vue'
  import { RouterLink } from 'vue-router'
  import { storeToRefs } from 'pinia'
  import { useCartStore } from '@/stores/cart.js'

  const cart = useCartStore()
  const { itemCount } = storeToRefs(cart)

  onMounted(() => {
    cart.loadCart()
  })

  const navLinks = [
    { to: '/', label: '首頁' },
    { to: '/courses', label: '課程方案' },
    { to: '/reservation', label: '預約課程' },
    { to: '/products', label: '補給商城' },
    { to: '/subscription', label: '會籍方案' },
  ]
</script>

<template>
  <aside class="navbar">
    <RouterLink to="/" class="brand">
      <span class="brand-mark">威力</span>
      <span class="brand-sub">健身房管理系統</span>
    </RouterLink>

    <nav class="nav-links">
      <RouterLink
        v-for="link in navLinks"
        :key="link.to"
        :to="link.to"
        class="nav-link"
      >
        {{ link.label }}
      </RouterLink>
      <RouterLink to="/cart" class="nav-link cart-link">
        購物車
        <span v-if="itemCount > 0" class="cart-badge">{{ itemCount }}</span>
      </RouterLink>
      <RouterLink to="/orders" class="nav-link">我的訂單</RouterLink>
    </nav>

    <div class="nav-footer">© 2026 威力健身房</div>
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
  .nav-link.router-link-exact-active {
    background: var(--accent);
    color: #fff;
    font-weight: 700;
  }

  .nav-footer {
    font-size: 0.72rem;
    color: var(--text-muted);
    text-align: center;
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
    .nav-footer { display: none; }
  }
</style>
