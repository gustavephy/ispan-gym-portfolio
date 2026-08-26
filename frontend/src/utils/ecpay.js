// 動態組出綠界要求的隱藏表單並送出，瀏覽器會被導到綠界付款頁。
// ActionUrl 是後端額外附加的目的地資訊，不屬於要送給綠界的欄位，簽章也沒算進去，這裡要排除。
//
// options.target：預設在同一分頁導向綠界；傳 '_blank' 則開新分頁（課程直接購買用）。
export function submitEcpayForm(fields, options = {}) {
  const { ActionUrl, ...formFields } = fields
  const form = document.createElement('form')
  form.method = 'POST'
  form.action = ActionUrl
  if (options.target) form.target = options.target
  for (const [key, value] of Object.entries(formFields)) {
    const input = document.createElement('input')
    input.type = 'hidden'
    input.name = key
    input.value = value
    form.appendChild(input)
  }
  document.body.appendChild(form)
  form.submit()
  // 新分頁送出後可以把 form 收掉；同分頁送出時瀏覽器正在導頁，移不移除都無所謂
  if (options.target === '_blank') form.remove()
}
