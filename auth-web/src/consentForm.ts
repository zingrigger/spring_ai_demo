export interface ConsentSubmission {
  clientId: string
  state: string | null
  scopes: string[]
}

/**
 * Spring Authorization Server 的授权确认必须由浏览器顶层表单 POST 完成：
 * 端点会把浏览器 302 回客户端的 redirect_uri，fetch 无法完成这个跳转。
 * 该端点忽略 CSRF（SAS 对其全部端点调用 csrf.ignoringRequestMatchers），因此无需 _csrf 字段。
 */
export function submitConsent(submission: ConsentSubmission): void {
  const form = document.createElement('form')
  form.method = 'post'
  form.action = '/oauth2/authorize'
  form.style.display = 'none'

  const fields: [string, string][] = [['client_id', submission.clientId]]
  if (submission.state) fields.push(['state', submission.state])
  submission.scopes.forEach((scope) => fields.push(['scope', scope]))

  fields.forEach(([name, value]) => {
    const input = document.createElement('input')
    input.type = 'hidden'
    input.name = name
    input.value = value
    form.appendChild(input)
  })

  document.body.appendChild(form)
  form.submit()
}
