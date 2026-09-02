<script setup>
/**
 * 營運分析：嵌入 Metabase 的「營運總覽」公開儀表板（見 bi/README.md）。
 *
 * 用的是 public link 的 iframe——無認證、拿到網址就能看，本機 Demo 可接受；
 * 正式環境要換 signed embedding（後端用 embed secret 簽短效 JWT）。
 * public link 重新產生時要同步更新這裡的常數。
 */
const METABASE_BASE = 'http://localhost:3001'
const PUBLIC_DASHBOARD_URL = `${METABASE_BASE}/public/dashboard/dda53c1f-30f4-460a-9f21-9b0f825e77e2`
</script>

<template>
  <div class="page-container analytics-page">
    <span class="section-tag">ADMIN</span>
    <h1 class="section-title">營運分析</h1>
    <p class="section-desc">
      Metabase 自助式 BI：這裡嵌的是「營運總覽」儀表板，頂端的日期選擇器可切換期間
      （未設定＝開站以來）；要自己拖拉新報表，
      <a :href="METABASE_BASE" target="_blank" rel="noopener">在 Metabase 開啟</a>（營運人員不用寫程式）。
      跟「營運統計」互補：對帳走快照報表，探索走這裡。
    </p>

    <div class="frame-card">
      <iframe
        :src="PUBLIC_DASHBOARD_URL"
        title="Metabase 營運總覽"
        allowtransparency
        loading="lazy"
      ></iframe>
    </div>
    <p class="frame-hint">
      看不到內容時，請確認 Metabase 容器有啟動（bi/ 下 docker compose up -d），
      或直接開 <a :href="PUBLIC_DASHBOARD_URL" target="_blank" rel="noopener">公開儀表板</a>。
    </p>
  </div>
</template>

<style scoped>
.analytics-page {
  padding-bottom: 2rem;
}

.frame-card {
  border: 1px solid var(--border-color, #e2ddd6);
  border-radius: 10px;
  overflow: hidden;
  background: #fff;
}

.frame-card iframe {
  display: block;
  width: 100%;
  height: 1150px;
  border: 0;
}

.frame-hint {
  margin-top: 0.6rem;
  font-size: 0.8rem;
  color: var(--text-muted, #9a8f88);
}
</style>
