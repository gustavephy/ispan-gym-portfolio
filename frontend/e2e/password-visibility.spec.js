import { test, expect, ACCOUNTS, loginAs } from './fixtures.js'

/**
 * 密碼欄位「顯示／隱藏」眼睛按鈕（共用元件 PasswordInput.vue）。
 *
 * <p>純前端行為，不打後端：驗的是切換 type、值不被清掉、aria-label 正確、
 * 按鈕不會送出表單、同頁多個欄位各自獨立、autocomplete 保留。
 */

/** 以欄位 id 取得該 PasswordInput 的 input 與眼睛按鈕 */
function pwField(page, id) {
  const wrap = page.locator('.pw-input', { has: page.locator(`#${id}`) })
  return { input: wrap.locator('input'), toggle: wrap.getByRole('button') }
}

test.describe('密碼顯示切換', () => {
  test('登入頁：切換 type、保留值、aria-label 隨狀態變化', async ({ page }) => {
    await page.goto('/login')
    const { input, toggle } = pwField(page, 'login-password')

    await input.fill('S3cret-pw')
    await expect(input).toHaveAttribute('type', 'password')
    await expect(toggle).toHaveAttribute('aria-label', '顯示密碼')

    await toggle.click()
    await expect(input).toHaveAttribute('type', 'text')
    await expect(input).toHaveValue('S3cret-pw')
    await expect(toggle).toHaveAttribute('aria-label', '隱藏密碼')

    await toggle.click()
    await expect(input).toHaveAttribute('type', 'password')
    await expect(input).toHaveValue('S3cret-pw')
    await expect(toggle).toHaveAttribute('aria-label', '顯示密碼')
  })

  test('眼睛按鈕可用鍵盤操作，且不會送出表單', async ({ page }) => {
    await page.goto('/login')
    const { input, toggle } = pwField(page, 'login-password')
    await page.getByLabel('Email').fill('someone@local.test')
    await input.fill('S3cret-pw')

    let loginPosted = false
    page.on('request', (r) => {
      if (r.url().includes('/api/auth/login') && r.method() === 'POST') loginPosted = true
    })

    await toggle.focus()
    await toggle.press('Enter')
    await expect(input).toHaveAttribute('type', 'text')
    await toggle.press(' ')
    await expect(input).toHaveAttribute('type', 'password')

    await expect(page).toHaveURL(/\/login/)
    expect(loginPosted, '切換顯示不應觸發登入請求').toBe(false)
  })

  test('會員註冊頁：密碼與確認密碼各自獨立切換', async ({ page }) => {
    await page.goto('/member-register')
    const pw = pwField(page, 'reg-password')
    const confirm = pwField(page, 'reg-confirm')

    await pw.input.fill('Abcd1234')
    await confirm.input.fill('Abcd1234')

    await pw.toggle.click()
    await expect(pw.input).toHaveAttribute('type', 'text')
    await expect(confirm.input).toHaveAttribute('type', 'password')

    await confirm.toggle.click()
    await expect(pw.input).toHaveAttribute('type', 'text')
    await expect(confirm.input).toHaveAttribute('type', 'text')
  })

  test('autocomplete 屬性照原樣轉發到 input', async ({ page }) => {
    await page.goto('/login')
    await expect(pwField(page, 'login-password').input).toHaveAttribute(
      'autocomplete',
      'current-password',
    )

    await page.goto('/member-register')
    await expect(pwField(page, 'reg-password').input).toHaveAttribute('autocomplete', 'new-password')
    await expect(pwField(page, 'reg-confirm').input).toHaveAttribute('autocomplete', 'new-password')
  })

  test('重設密碼頁：兩欄初始都是隱藏', async ({ page }) => {
    await page.goto('/reset-password?token=dummy-token-for-render')
    await expect(pwField(page, 'reset-password').input).toHaveAttribute('type', 'password')
    await expect(pwField(page, 'reset-confirm').input).toHaveAttribute('type', 'password')
  })

  test('帳號安全頁：修改密碼三欄各自獨立切換', async ({ page }) => {
    await loginAs(page, ACCOUNTS.member)
    await page.goto('/account/security')

    const cur = pwField(page, 'sec-current-password')
    const next = pwField(page, 'sec-new-password')
    const confirm = pwField(page, 'sec-confirm-password')

    await cur.toggle.click()
    await expect(cur.input).toHaveAttribute('type', 'text')
    await expect(next.input).toHaveAttribute('type', 'password')
    await expect(confirm.input).toHaveAttribute('type', 'password')
  })
})
