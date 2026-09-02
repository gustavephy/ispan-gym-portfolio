<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { RouterLink } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useCartStore } from '@/stores/cart.js'
import { checkoutFromCart, previewOrder } from '@/api/order.js'
import { getMyProfile } from '@/api/memberProfile.js'
import { getMyUnusedCoupons } from '@/api/member/coupon.js'
import { useEcpayCheckout } from '@/composables/useEcpayCheckout.js'
import { useMediaQuery } from '@/composables/useMediaQuery.js'
import { useFeedback } from '@/composables/useFeedback.js'
import { taiwanDistricts } from '@/data/taiwanDistricts.js'
import { formatImageUrl, useFallbackImage, DEFAULT_PRODUCT_IMAGE } from '@/utils/image.js'
import FeedbackToast from '@/components/FeedbackToast.vue'
import EmptyState from '@/components/EmptyState.vue'
import OrderSummaryCard from '@/components/OrderSummaryCard.vue'

const cart = useCartStore()
const { items, totalAmount, loading } = storeToRefs(cart)
const { redirectToEcpay } = useEcpayCheckout()
const isNarrow = useMediaQuery('(max-width: 768px)')

// 選中的優惠券代碼（空字串＝不使用）
const couponCode = ref('')
const receiverName = ref('')
const receiverPhone = ref('')
const submitting = ref(false)
const showConfirm = ref(false)

// ── 我的優惠券下拉挑選 ─────────────────────────────────────────────────
// 後端結帳規則：只有「已領取且未使用」的券才能折抵，所以不提供手動輸入代碼，
// 一律從名下未使用清單挑（再過濾成適用商品的：applicableType 為 ALL 或 PRODUCT）
const unusedCoupons = ref([])
const applicableCoupons = computed(() =>
  unusedCoupons.value.filter((mc) => ['ALL', 'PRODUCT'].includes(mc.coupon?.applicableType)),
)

async function loadCoupons() {
  try {
    const { data } = await getMyUnusedCoupons()
    unusedCoupons.value = Array.isArray(data) ? data : []
  } catch {
    unusedCoupons.value = []
  }
}

function couponDiscountLabel(coupon) {
  if (!coupon) return ''
  return coupon.type === 'PERCENT'
    ? `省 ${Number(coupon.value)}%`
    : `折 NT$ ${Number(coupon.value).toLocaleString()}`
}

// ── 結帳前試算：選券後即時算折抵與應付金額 ────────────────────────────
const preview = ref(null)
let previewTimer = null

async function runPreview() {
  try {
    const { data } = await previewOrder({ couponCode: couponCode.value.trim() || null })
    preview.value = data
  } catch {
    // 試算失敗不擋結帳，畫面退回只顯示小計
    preview.value = null
  }
}

watch(couponCode, () => {
  clearTimeout(previewTimer)
  previewTimer = setTimeout(runPreview, 400)
})

const subtotal = computed(() => Number(preview.value?.subtotalAmount ?? totalAmount.value))
const discount = computed(() => Number(preview.value?.discountAmount ?? 0))
const payableTotal = computed(() => Number(preview.value?.totalAmount ?? totalAmount.value))
const couponRejected = computed(
  () => !!couponCode.value.trim() && preview.value && !preview.value.couponApplied,
)

// ── 發票資訊（僅蒐集儲存＋顯示，未串綠界電子發票開立） ───────────────
const invoiceType = ref('PERSONAL_CARRIER')
const invoiceCarrier = ref('')
const invoiceTaxId = ref('')
const invoiceDonationCode = ref('')
const invoiceOptions = [
  {
    value: 'PERSONAL_CARRIER',
    label: '個人（會員載具）',
    desc: '歸戶至您的會員雲端發票，不需額外資料',
  },
  { value: 'MOBILE_BARCODE', label: '手機條碼載具', desc: '輸入手機條碼（斜線加 7 碼大寫英數）' },
  { value: 'COMPANY', label: '公司統編', desc: '開立三聯式發票，輸入 8 碼統一編號' },
  { value: 'NPO_DONATION', label: '捐贈發票', desc: '捐贈給社福機構，輸入愛心碼' },
]
const MOBILE_BARCODE_RE = /^\/[0-9A-Z.+-]{7}$/
const TAX_ID_RE = /^\d{8}$/
const DONATION_RE = /^\d{2,7}$/
const invoiceError = computed(() => {
  if (
    invoiceType.value === 'MOBILE_BARCODE' &&
    !MOBILE_BARCODE_RE.test(invoiceCarrier.value.trim())
  ) {
    return '手機條碼格式不正確，例如 /ABC+123'
  }
  if (invoiceType.value === 'COMPANY' && !TAX_ID_RE.test(invoiceTaxId.value.trim())) {
    return '統一編號需為 8 碼數字'
  }
  if (invoiceType.value === 'NPO_DONATION' && !DONATION_RE.test(invoiceDonationCode.value.trim())) {
    return '愛心捐贈碼需為 2~7 碼數字'
  }
  return ''
})
const invoiceSummary = computed(() => {
  const opt = invoiceOptions.find((o) => o.value === invoiceType.value)
  if (invoiceType.value === 'MOBILE_BARCODE') return `手機條碼載具　${invoiceCarrier.value.trim()}`
  if (invoiceType.value === 'COMPANY') return `公司統編　${invoiceTaxId.value.trim()}`
  if (invoiceType.value === 'NPO_DONATION')
    return `捐贈發票　愛心碼 ${invoiceDonationCode.value.trim()}`
  return opt?.label ?? ''
})
function invoicePayload() {
  return {
    invoiceType: invoiceType.value,
    invoiceCarrier: invoiceType.value === 'MOBILE_BARCODE' ? invoiceCarrier.value.trim() : null,
    invoiceTaxId: invoiceType.value === 'COMPANY' ? invoiceTaxId.value.trim() : null,
    invoiceDonationCode:
      invoiceType.value === 'NPO_DONATION' ? invoiceDonationCode.value.trim() : null,
  }
}

// 付款方式：Credit（信用卡，即時）／ATM（虛擬帳號轉帳）／CVS（超商代碼繳費）——
// 後兩者不是即時付款，綠界會先給一組帳號/代碼，消費者之後自行完成繳費
const paymentMethod = ref('Credit')
const paymentMethodOptions = [
  { value: 'Credit', label: '信用卡', desc: '導向綠界頁面輸入卡號，即時完成付款' },
  { value: 'ATM', label: 'ATM 轉帳', desc: '取得虛擬帳號，請於期限內完成轉帳' },
  { value: 'CVS', label: '超商代碼', desc: '取得繳費代碼，請至超商完成繳費' },
]
const paymentMethodLabel = computed(
  () => paymentMethodOptions.find((o) => o.value === paymentMethod.value)?.label ?? '',
)

// 超商代碼繳費金額限制（含手續費），跟後端 EcpayService 的檢查一致；用折抵後的應付金額判斷
const CVS_MIN_AMOUNT = 27
const CVS_MAX_AMOUNT = 20000
const isCvsAllowed = computed(
  () => payableTotal.value >= CVS_MIN_AMOUNT && payableTotal.value <= CVS_MAX_AMOUNT,
)
// 訂單金額變動（例如優惠券折抵後）導致超商代碼不再適用時，自動退回信用卡，避免送出後被後端擋下
watch(isCvsAllowed, (allowed) => {
  if (!allowed && paymentMethod.value === 'CVS') paymentMethod.value = 'Credit'
})

const paymentMethodNote = computed(() => {
  if (paymentMethod.value === 'ATM') {
    return '送出後將導向綠界測試環境取得轉帳虛擬帳號，請於期限內完成轉帳；付款結果需要一些時間才會更新，可至「我的訂單」查詢繳費資訊'
  }
  if (paymentMethod.value === 'CVS') {
    return '送出後將導向綠界測試環境取得超商代碼，請至超商完成繳費；付款結果需要一些時間才會更新，可至「我的訂單」查詢繳費資訊'
  }
  return '目前僅支援信用卡付款，送出後將導向綠界測試付款頁輸入卡號完成付款'
})
const paymentMethodConfirmNote = computed(() => {
  if (paymentMethod.value === 'ATM') return '確認無誤後將導向綠界頁面取得轉帳用虛擬帳號'
  if (paymentMethod.value === 'CVS') return '確認無誤後將導向綠界頁面取得超商繳費代碼'
  return '確認無誤後將導向綠界測試付款頁，僅支援信用卡付款'
})

// 收件地址拆成縣市/行政區下拉 + 詳細地址（路名、巷弄、門牌）自由輸入
const cityOptions = Object.keys(taiwanDistricts)
const receiverCity = ref('')
const receiverDistrict = ref('')
const receiverAddressDetail = ref('')
const districtOptions = computed(() =>
  receiverCity.value ? (taiwanDistricts[receiverCity.value] ?? []) : [],
)

// 換縣市時，原本選的行政區不一定屬於新縣市，直接清空避免留著兜不起來的組合
watch(receiverCity, () => {
  receiverDistrict.value = ''
})

// 這次結帳的冪等金鑰：整個頁面存續期間固定不變，手滑連點「確認送出」
// 只會真正建立一次訂單；離開頁面重新進來（新的一次結帳）才會換一把新的
const idempotencyKey = crypto.randomUUID()

const { feedback, showFeedback } = useFeedback()

// 會員資料裡已存的地址，用「區塊式選項」讓使用者在它跟自訂收件資訊之間切換，
// 不會直接把文字灌進欄位——要改用別的地址時點一下切換就是空白表單，不用先刪掉舊字
const memberProfile = ref(null)
const receiverMode = ref('custom') // 'profile' | 'custom'

const hasProfileAddress = computed(() =>
  Boolean(
    memberProfile.value?.city || memberProfile.value?.district || memberProfile.value?.address,
  ),
)
const profileName = computed(() =>
  memberProfile.value
    ? `${memberProfile.value.firstName ?? ''}${memberProfile.value.lastName ?? ''}`.trim()
    : '',
)
const profilePhone = computed(() => memberProfile.value?.phone ?? '')
const profileAddress = computed(() =>
  memberProfile.value
    ? [memberProfile.value.city, memberProfile.value.district, memberProfile.value.address]
        .filter(Boolean)
        .join('')
    : '',
)

// 實際要送出/在確認彈窗顯示的收件資訊，依目前選的模式決定來源；
// 自訂模式的地址由縣市＋行政區＋詳細地址組成，台灣地址慣例不加空格
const effectiveReceiver = computed(() => {
  if (receiverMode.value === 'profile') {
    return { name: profileName.value, phone: profilePhone.value, address: profileAddress.value }
  }
  const address = `${receiverCity.value}${receiverDistrict.value}${receiverAddressDetail.value.trim()}`
  return { name: receiverName.value.trim(), phone: receiverPhone.value.trim(), address }
})

async function loadMemberProfile() {
  try {
    const { data } = await getMyProfile()
    memberProfile.value = data
    // 城市/行政區/詳細地址任一有值就當作有地址資料，預設選它，省得每次都要手動切
    if (data.city || data.district || data.address) receiverMode.value = 'profile'
  } catch {
    // 抓不到就維持自訂模式，使用者手動輸入即可
  }
}

// 購物車有任一品項已下架或庫存不足時擋下結帳
const hasUnavailableItem = computed(() => items.value.some((i) => i.available === false))

// 按「確認結帳並前往付款」先開確認彈窗讓使用者再檢查一次，不是直接送出
function openConfirm() {
  if (items.value.length === 0) {
    showFeedback('購物車是空的，無法結帳', 'error')
    return
  }
  if (hasUnavailableItem.value) {
    showFeedback('購物車有商品已下架或庫存不足，請先回購物車移除', 'error')
    return
  }
  if (!effectiveReceiver.value.address) {
    showFeedback('請填寫收件地址', 'error')
    return
  }
  if (invoiceError.value) {
    showFeedback(invoiceError.value, 'error')
    return
  }
  showConfirm.value = true
}

async function handleCheckout() {
  submitting.value = true
  try {
    const receiver = effectiveReceiver.value
    const orderRes = await checkoutFromCart(
      {
        couponCode: couponCode.value.trim() || null,
        receiverName: receiver.name || null,
        receiverPhone: receiver.phone || null,
        receiverAddress: receiver.address || null,
        ...invoicePayload(),
      },
      idempotencyKey,
    )
    const orderId = orderRes.data.orderId
    cart.reset() // 訂單一旦建立後端就已清空購物車，本地狀態在這裡就同步清空即可，不必等組表單成功

    // 取綠界表單 → 記下待確認訂單 → 顯示全屏過場 → 送出導向綠界（見 useEcpayCheckout）
    await redirectToEcpay(orderId, { method: paymentMethod.value })
    // 成功時瀏覽器即將離開本頁（導向綠界），過場遮罩由 EcpayRedirectOverlay 負責
  } catch (err) {
    // 攔截器已正規化錯誤，訊息在 err.message；err.response 不存在（見 normalizeApiError）
    showConfirm.value = false
    showFeedback(err.message || '結帳失敗，請稍後再試', 'error')
    submitting.value = false
  }
}

onMounted(() => {
  cart.loadCart()
  loadMemberProfile()
  loadCoupons()
  runPreview()
})

onUnmounted(() => clearTimeout(previewTimer))
</script>

<template>
  <div class="page-container">
    <h1 class="section-title">確認結帳</h1>

    <div v-if="loading" class="spinner"></div>

    <EmptyState
      v-else-if="items.length === 0"
      icon="cart"
      title="購物車是空的，無法結帳"
      description="先去挑幾件商品，再回來完成結帳"
    >
      <template #action>
        <RouterLink to="/products" class="btn btn-accent">去逛逛</RouterLink>
      </template>
    </EmptyState>

    <div v-else class="checkout-layout">
      <div class="checkout-form">
        <div class="form-card">
          <h3>訂單商品</h3>
          <div
            v-for="item in items"
            :key="item.cartItemId"
            class="order-item-row order-item-row--rich"
          >
            <div class="order-item-thumb">
              <img
                v-if="item.imageUrl"
                :src="formatImageUrl(item.imageUrl)"
                :alt="item.productName"
                @error="useFallbackImage($event, DEFAULT_PRODUCT_IMAGE)"
              />
              <span v-else>{{ item.productName?.[0] }}</span>
            </div>
            <span class="order-item-name">
              {{ item.productName }} × {{ item.quantity }}
              <span v-if="item.available === false" class="item-flag danger"
                >已下架 / 庫存不足</span
              >
              <span v-else-if="item.priceChanged" class="item-flag warn">
                價格已變動（加入時 NT$ {{ Number(item.priceWhenAdded).toLocaleString() }}）
              </span>
            </span>
            <span class="order-item-amount">NT$ {{ Number(item.subtotal).toLocaleString() }}</span>
          </div>
          <p v-if="hasUnavailableItem" class="checkout-blocker">
            購物車有商品已下架或庫存不足，請先
            <RouterLink to="/cart">回購物車</RouterLink>
            移除後再結帳。
          </p>
        </div>

        <div class="form-card">
          <h3>收件資訊<span class="req">＊</span></h3>

          <div
            v-if="hasProfileAddress"
            class="receiver-mode-select"
            role="group"
            aria-label="收件資訊來源"
          >
            <button
              type="button"
              class="receiver-mode-option"
              :class="{ active: receiverMode === 'profile' }"
              :aria-pressed="receiverMode === 'profile'"
              @click="receiverMode = 'profile'"
            >
              <span class="receiver-mode-label">使用會員資料地址</span>
              <span class="receiver-mode-preview">{{ profileName }}　{{ profilePhone }}</span>
              <span class="receiver-mode-preview">{{ profileAddress }}</span>
            </button>
            <button
              type="button"
              class="receiver-mode-option"
              :class="{ active: receiverMode === 'custom' }"
              :aria-pressed="receiverMode === 'custom'"
              @click="receiverMode = 'custom'"
            >
              <span class="receiver-mode-label">填寫其他收件資訊</span>
              <span class="receiver-mode-preview">為這筆訂單指定不同的收件人</span>
            </button>
          </div>

          <div v-if="receiverMode === 'custom'" class="receiver-fields">
            <div class="field-group">
              <label>收件人姓名</label>
              <input v-model="receiverName" class="form-input" placeholder="選填" />
            </div>
            <div class="field-group">
              <label>收件人電話</label>
              <input v-model="receiverPhone" class="form-input" placeholder="選填" />
            </div>
            <div class="field-group">
              <label>收件地址<span class="req">＊</span></label>
              <div class="address-region-row">
                <select v-model="receiverCity" class="form-input region-select">
                  <option value="">縣市</option>
                  <option v-for="city in cityOptions" :key="city" :value="city">{{ city }}</option>
                </select>
                <select
                  v-model="receiverDistrict"
                  class="form-input region-select"
                  :disabled="!receiverCity"
                >
                  <option value="">行政區</option>
                  <option v-for="d in districtOptions" :key="d" :value="d">{{ d }}</option>
                </select>
              </div>
              <input
                v-model="receiverAddressDetail"
                class="form-input"
                placeholder="路名、巷弄、門牌號碼等詳細地址"
              />
            </div>
          </div>
        </div>

        <div class="form-card">
          <h3>付款方式</h3>
          <div class="payment-method-select" role="group" aria-label="付款方式">
            <button
              v-for="opt in paymentMethodOptions"
              :key="opt.value"
              type="button"
              class="payment-method-option"
              :class="{ active: paymentMethod === opt.value }"
              :disabled="opt.value === 'CVS' && !isCvsAllowed"
              :aria-pressed="paymentMethod === opt.value"
              @click="paymentMethod = opt.value"
            >
              <span class="payment-method-option-label">{{ opt.label }}</span>
              <span class="payment-method-option-desc">{{ opt.desc }}</span>
            </button>
          </div>
          <p v-if="!isCvsAllowed" class="payment-method-hint">
            超商代碼僅支援 NT$27～NT$20,000 的訂單金額，本筆訂單金額不符，已停用此選項
          </p>
        </div>

        <div class="form-card">
          <h3>發票資訊</h3>
          <div class="invoice-select" role="group" aria-label="發票開立方式">
            <button
              v-for="opt in invoiceOptions"
              :key="opt.value"
              type="button"
              class="payment-method-option"
              :class="{ active: invoiceType === opt.value }"
              :aria-pressed="invoiceType === opt.value"
              @click="invoiceType = opt.value"
            >
              <span class="payment-method-option-label">{{ opt.label }}</span>
              <span class="payment-method-option-desc">{{ opt.desc }}</span>
            </button>
          </div>
          <div v-if="invoiceType === 'MOBILE_BARCODE'" class="field-group invoice-field">
            <label>手機條碼</label>
            <input v-model="invoiceCarrier" class="form-input" placeholder="例如 /ABC+123" />
          </div>
          <div v-else-if="invoiceType === 'COMPANY'" class="field-group invoice-field">
            <label>統一編號</label>
            <input v-model="invoiceTaxId" class="form-input" placeholder="8 碼數字" maxlength="8" />
          </div>
          <div v-else-if="invoiceType === 'NPO_DONATION'" class="field-group invoice-field">
            <label>愛心捐贈碼</label>
            <input
              v-model="invoiceDonationCode"
              class="form-input"
              placeholder="2~7 碼數字"
              maxlength="7"
            />
          </div>
          <p v-if="invoiceError" class="payment-method-hint">{{ invoiceError }}</p>
        </div>

        <div class="form-card">
          <h3>優惠券（選填）</h3>
          <div
            v-if="applicableCoupons.length"
            class="coupon-select"
            role="radiogroup"
            aria-label="選擇優惠券"
          >
            <label class="coupon-option" :class="{ active: couponCode === '' }">
              <input type="radio" name="coupon" value="" v-model="couponCode" />
              <span class="coupon-option__label">不使用優惠券</span>
            </label>
            <label
              v-for="mc in applicableCoupons"
              :key="mc.memberCouponId"
              class="coupon-option"
              :class="{ active: couponCode === mc.coupon.code }"
            >
              <input type="radio" name="coupon" :value="mc.coupon.code" v-model="couponCode" />
              <span class="coupon-option__label">{{ mc.coupon.name }}</span>
              <span class="coupon-option__meta">
                {{ couponDiscountLabel(mc.coupon) }}
                <template v-if="Number(mc.coupon.minSpend) > 0">
                  ・滿 NT$ {{ Number(mc.coupon.minSpend).toLocaleString() }}
                </template>
              </span>
            </label>
          </div>
          <p v-else class="coupon-empty">
            目前沒有可用於商品的優惠券，可至
            <RouterLink to="/coupons">優惠券專區</RouterLink>
            領取。
          </p>
          <p v-if="couponRejected" class="payment-method-hint" role="alert">
            {{ preview.couponMessage }}
          </p>
        </div>
      </div>

      <OrderSummaryCard title="付款金額" total-label="應付金額" :total-value="payableTotal" sticky>
        <template #rows>
          <div class="summary-card__row">
            <span>商品小計</span>
            <span>NT$ {{ subtotal.toLocaleString() }}</span>
          </div>
          <div v-if="discount > 0" class="summary-card__row discount-row">
            <span>優惠折抵{{ preview?.couponName ? `（${preview.couponName}）` : '' }}</span>
            <span>− NT$ {{ discount.toLocaleString() }}</span>
          </div>
        </template>
        <div class="payment-method-row">
          <span class="payment-method-label">付款方式</span>
          <span class="payment-method-value">{{ paymentMethodLabel }}（綠界 ECPay）</span>
        </div>
        <p class="ecpay-note">{{ paymentMethodNote }}</p>
        <button
          v-if="!isNarrow"
          class="btn btn-accent submit-btn"
          :disabled="submitting || hasUnavailableItem || !!invoiceError"
          @click="openConfirm"
        >
          確認結帳並前往付款
        </button>
      </OrderSummaryCard>
    </div>

    <div v-if="isNarrow && !loading && items.length" class="mobile-checkout-bar">
      <div class="mobile-checkout-bar__total">
        <span>應付金額</span>
        <strong>NT$ {{ payableTotal.toLocaleString() }}</strong>
      </div>
      <button
        class="btn btn-accent mobile-checkout-bar__btn"
        :disabled="submitting || hasUnavailableItem || !!invoiceError"
        @click="openConfirm"
      >
        確認結帳並前往付款
      </button>
    </div>

    <div
      v-if="showConfirm"
      class="modal-overlay"
      @click.self="!submitting && (showConfirm = false)"
    >
      <div class="modal-content">
        <h3 class="modal-title">確認訂單內容</h3>

        <div class="confirm-section">
          <h4 class="confirm-section-title">訂單商品</h4>
          <div v-for="item in items" :key="item.cartItemId" class="order-item-row">
            <span class="order-item-name">{{ item.productName }} × {{ item.quantity }}</span>
            <span class="order-item-amount">NT$ {{ Number(item.subtotal).toLocaleString() }}</span>
          </div>
        </div>

        <div class="confirm-section">
          <h4 class="confirm-section-title">收件資訊</h4>
          <p class="confirm-text">
            {{ effectiveReceiver.name || '未填寫' }}　{{ effectiveReceiver.phone }}
          </p>
          <p class="confirm-text">{{ effectiveReceiver.address || '未填寫地址' }}</p>
        </div>

        <div class="confirm-section">
          <h4 class="confirm-section-title">發票</h4>
          <p class="confirm-text">{{ invoiceSummary }}</p>
        </div>

        <div class="confirm-section">
          <h4 class="confirm-section-title">付款方式</h4>
          <p class="confirm-text">{{ paymentMethodLabel }}</p>
        </div>

        <div class="confirm-amount-lines">
          <div class="confirm-amount-row">
            <span>商品小計</span>
            <span>NT$ {{ subtotal.toLocaleString() }}</span>
          </div>
          <div v-if="discount > 0" class="confirm-amount-row discount-row">
            <span>優惠折抵{{ preview?.couponName ? `（${preview.couponName}）` : '' }}</span>
            <span>− NT$ {{ discount.toLocaleString() }}</span>
          </div>
        </div>
        <div class="confirm-total-row">
          <span>應付金額</span>
          <span class="total-price">NT$ {{ payableTotal.toLocaleString() }}</span>
        </div>
        <p class="ecpay-note">{{ paymentMethodConfirmNote }}</p>

        <div class="modal-actions">
          <button
            class="btn btn-accent"
            type="button"
            :disabled="submitting"
            @click="handleCheckout"
          >
            {{ submitting ? '處理中...' : '確認送出，前往付款' }}
          </button>
          <button
            class="btn btn-outline"
            type="button"
            :disabled="submitting"
            @click="showConfirm = false"
          >
            返回修改
          </button>
        </div>
      </div>
    </div>

    <FeedbackToast :feedback="feedback" />
  </div>
</template>

<style scoped>
.checkout-layout {
  display: grid;
  grid-template-columns: 1fr 320px;
  gap: 1.5rem;
  align-items: start;
  margin-top: 2rem;
}
.checkout-form {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}
.form-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-section);
  padding: 1.25rem 1.5rem;
}
.form-card h3 {
  font-size: 0.95rem;
  font-weight: 700;
  margin-bottom: 1rem;
}
.req {
  color: #c92a2a;
  margin-left: 0.15rem;
}
.item-flag {
  display: inline-block;
  margin-left: 0.5rem;
  padding: 0.05rem 0.4rem;
  border-radius: 4px;
  font-size: 0.72rem;
  font-weight: 600;
  vertical-align: middle;
}
.item-flag.danger {
  background: #fde2e2;
  color: #c92a2a;
}
.item-flag.warn {
  background: #fff3cd;
  color: #a06400;
}
.checkout-blocker {
  margin-top: 0.75rem;
  font-size: 0.82rem;
  color: #c92a2a;
}
.checkout-blocker a {
  color: var(--accent-hover);
  font-weight: 600;
}
.order-item-row {
  display: flex;
  justify-content: space-between;
  font-size: 0.88rem;
  color: var(--text-secondary);
  padding: 0.35rem 0;
}
.order-item-row--rich {
  align-items: center;
  gap: 0.75rem;
}
.order-item-thumb {
  width: 44px;
  height: 44px;
  flex-shrink: 0;
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--bg-soft);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1rem;
  color: var(--text-muted);
}
.order-item-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.order-item-row--rich .order-item-name {
  flex: 1;
  min-width: 0;
}

/* 發票／優惠券挑選卡 */
.invoice-select {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.6rem;
}
.invoice-field {
  margin-top: 0.9rem;
}
.coupon-select {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.coupon-option {
  display: grid;
  grid-template-columns: auto 1fr;
  align-items: center;
  column-gap: 0.6rem;
  padding: 0.65rem 0.85rem;
  border: 1.5px solid var(--border);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: var(--transition);
}
.coupon-option:hover {
  border-color: var(--accent);
}
.coupon-option.active {
  border-color: var(--accent);
  background: var(--bg-soft);
}
.coupon-option__label {
  font-size: 0.88rem;
  font-weight: 600;
  color: var(--text-primary);
}
.coupon-option__meta {
  grid-column: 2;
  font-size: 0.78rem;
  color: var(--text-muted);
}
.coupon-empty {
  font-size: 0.85rem;
  color: var(--text-secondary);
}
.coupon-empty a {
  color: var(--accent-hover);
  font-weight: 600;
}

/* OrderSummaryCard #rows slot 的內容帶的是本元件的 scope，需在這裡定義樣式 */
.summary-card__row {
  display: flex;
  justify-content: space-between;
  margin-bottom: 0.75rem;
  font-size: 0.9rem;
  color: var(--text-secondary);
}
.discount-row {
  color: #c92a2a;
}
.confirm-amount-lines {
  margin-top: 1rem;
  padding-top: 0.75rem;
  border-top: 1px dashed var(--border);
}
.confirm-amount-row {
  display: flex;
  justify-content: space-between;
  font-size: 0.85rem;
  color: var(--text-secondary);
  margin-bottom: 0.35rem;
}
.confirm-amount-row.discount-row {
  color: #c92a2a;
}
.receiver-mode-select {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.75rem;
  margin-bottom: 1rem;
}
.receiver-mode-option {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  text-align: left;
  padding: 0.75rem 0.9rem;
  border: 1.5px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-section);
  cursor: pointer;
  transition: var(--transition);
}
.receiver-mode-option:hover {
  border-color: var(--accent);
}
.receiver-mode-option.active {
  border-color: var(--accent);
  background: var(--bg-soft);
}
.receiver-mode-label {
  font-size: 0.85rem;
  font-weight: 700;
  color: var(--text-primary);
}
.receiver-mode-option.active .receiver-mode-label {
  color: var(--accent-hover);
}
.receiver-mode-preview {
  font-size: 0.78rem;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.receiver-fields {
  padding-top: 0.25rem;
}
.field-group {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  margin-bottom: 0.9rem;
}
.field-group:last-child {
  margin-bottom: 0;
}
.field-group label {
  font-size: 0.82rem;
  color: var(--text-secondary);
}
.form-input {
  padding: 0.55rem 0.8rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 0.88rem;
}
.address-region-row {
  display: flex;
  gap: 0.6rem;
  margin-bottom: 0.6rem;
}
.region-select {
  flex: 1;
  min-width: 0;
  background: var(--bg-section);
}
.region-select:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.payment-method-select {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0.75rem;
}
.payment-method-option {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  text-align: left;
  padding: 0.75rem 0.9rem;
  border: 1.5px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-section);
  cursor: pointer;
  transition: var(--transition);
  font-family: inherit;
}
.payment-method-option:hover:not(:disabled) {
  border-color: var(--accent);
}
.payment-method-option.active {
  border-color: var(--accent);
  background: var(--bg-soft);
}
.payment-method-option:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.payment-method-option-label {
  font-size: 0.85rem;
  font-weight: 700;
  color: var(--text-primary);
}
.payment-method-option.active .payment-method-option-label {
  color: var(--accent-hover);
}
.payment-method-option-desc {
  font-size: 0.76rem;
  color: var(--text-muted);
}
.payment-method-hint {
  margin-top: 0.6rem;
  font-size: 0.78rem;
  color: #a06400;
}

/* modal 內的應付金額沿用這個色與字級（摘要卡的合計已由 OrderSummaryCard 處理） */
.total-price {
  color: var(--accent-hover);
  font-size: 1.2rem;
}
.payment-method-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 1rem;
  padding: 0.6rem 0.8rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-soft);
  font-size: 0.85rem;
}
.payment-method-label {
  color: var(--text-secondary);
}
.payment-method-value {
  font-weight: 700;
  color: var(--text-primary);
}
.ecpay-note {
  font-size: 0.75rem;
  color: var(--text-muted);
  margin-top: 0.75rem;
  text-align: center;
}
.submit-btn {
  width: 100%;
  margin-top: 0.75rem;
  padding: 0.8rem;
}

.mobile-checkout-bar {
  display: none;
}

@media (max-width: 768px) {
  .checkout-layout {
    grid-template-columns: 1fr;
  }
  .receiver-mode-select {
    grid-template-columns: 1fr;
  }
  .payment-method-select {
    grid-template-columns: 1fr;
  }
  .invoice-select {
    grid-template-columns: 1fr;
  }
  .page-container {
    padding-bottom: 5.5rem;
  }
  /* 底部固定條已提供主 CTA，摘要卡內的送出鈕收起 */
  .submit-btn {
    display: none;
  }
  .mobile-checkout-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    z-index: 90;
    padding: 0.75rem 1.1rem calc(0.75rem + env(safe-area-inset-bottom, 0px));
    background: var(--bg-section);
    border-top: 1px solid var(--border);
    box-shadow: 0 -4px 16px rgba(0, 0, 0, 0.08);
  }
  .mobile-checkout-bar__total {
    display: flex;
    flex-direction: column;
    font-size: 0.72rem;
    color: var(--text-muted);
  }
  .mobile-checkout-bar__total strong {
    font-size: 1.05rem;
    color: var(--accent-hover);
  }
  .mobile-checkout-bar__btn {
    flex-shrink: 0;
    padding: 0.7rem 1.2rem;
    font-size: 0.85rem;
  }
}

.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1.5rem;
  z-index: 100;
}
.modal-content {
  width: 100%;
  max-width: 440px;
  max-height: 85vh;
  overflow-y: auto;
  background: var(--bg-section);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-hover);
  padding: 1.75rem;
}
.modal-title {
  margin: 0 0 1.25rem;
  font-size: 1.15rem;
  color: var(--text-primary);
}
.confirm-section {
  margin-bottom: 1.1rem;
  padding-bottom: 1.1rem;
  border-bottom: 1px dashed var(--border);
}
.confirm-section-title {
  font-size: 0.8rem;
  font-weight: 700;
  color: var(--text-muted);
  margin-bottom: 0.4rem;
}
.confirm-text {
  font-size: 0.88rem;
  color: var(--text-secondary);
  margin: 0.15rem 0;
}
.confirm-total-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-weight: 700;
  font-size: 1rem;
  margin-top: 1rem;
}
.modal-actions {
  display: flex;
  gap: 0.75rem;
  margin-top: 1.25rem;
}
.modal-actions .btn {
  flex: 1;
}
</style>
