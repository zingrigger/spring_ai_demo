import { createRouter, createWebHistory, type RouteLocationNormalized } from 'vue-router'
import { getSession, type SessionState } from './api/auth'
import { navigate } from './navigation'

export type GuardResult = boolean | { path: string }

/**
 * 会话守卫：未登录去 /login；平台管理员放行 /admin/**；其余用户先绑定组织；
 * 平台管理员可以是无组织账号，可以停留在首页，不会被组织选择页拦住；
 * 已有待处理授权请求时（仅在 / 与 /login 上）恢复该请求，避免打断组织与 consent 步骤。
 */
export function createSessionGuard(load: () => Promise<SessionState>) {
  return async (to: Pick<RouteLocationNormalized, 'path'>): Promise<GuardResult> => {
    const session = await load()
    if (!session.authenticated) {
      return to.path === '/login' ? true : { path: '/login' }
    }
    if (to.path.startsWith('/admin')) {
      return session.platformAdmin ? true : { path: '/' }
    }
    if (!session.organization) {
      if (to.path === '/organizations') {
        return true
      }
      if (session.platformAdmin) {
        if (to.path === '/') {
          return true
        }
        return { path: to.path === '/login' ? '/' : '/organizations' }
      }
      return { path: '/organizations' }
    }
    if (session.pending && (to.path === '/' || to.path === '/login')) {
      navigate(session.pending)
      return false
    }
    if (to.path === '/login' || to.path === '/organizations') {
      return { path: '/' }
    }
    return true
  }
}

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: () => import('./views/HomeView.vue') },
    { path: '/login', component: () => import('./views/LoginView.vue') },
    { path: '/organizations', component: () => import('./views/OrganizationsView.vue') },
    { path: '/consent', component: () => import('./views/ConsentView.vue') },
    { path: '/admin/clients', component: () => import('./views/admin/ClientListView.vue') },
    { path: '/admin/clients/new', component: () => import('./views/admin/ClientCreateView.vue') },
    { path: '/admin/clients/:clientId', component: () => import('./views/admin/ClientDetailView.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(createSessionGuard(getSession))
