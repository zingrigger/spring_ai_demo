/**
 * 顶层导航：next 可能是 SPA 路由（/organizations）也可能是协议地址
 * （/oauth2/authorize?…），统一交给浏览器处理，保证服务端能继续 302。
 */
export function navigate(url: string): void {
  window.location.assign(url)
}
