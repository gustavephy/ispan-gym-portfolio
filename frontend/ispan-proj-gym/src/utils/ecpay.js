// 動態組出綠界要求的隱藏表單並送出，瀏覽器會被導到綠界付款頁。
// ActionUrl 是後端額外附加的目的地資訊，不屬於要送給綠界的欄位，簽章也沒算進去，這裡要排除。
export function submitEcpayForm(fields) {
  const { ActionUrl, ...formFields } = fields
  const form = document.createElement('form')
  form.method = 'POST'
  form.action = ActionUrl
  for (const [key, value] of Object.entries(formFields)) {
    const input = document.createElement('input')
    input.type = 'hidden'
    input.name = key
    input.value = value
    form.appendChild(input)
  }
  document.body.appendChild(form)
  form.submit()
}
