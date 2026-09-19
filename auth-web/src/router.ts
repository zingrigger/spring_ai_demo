import { createRouter, createWebHistory, type RouteLocationNormalized } from 'vue-router'
import { getSession, type SessionState } from './api/auth'
import { navigate } from './navigation'

export type GuardResult = boolean | { path: string }

/**
 * 会话守卫：未登录去 /login；已登录未绑定组织去 /organizations；
 * 已有待处理授权请求时（仅在 / 与 /login 上）恢复该请求，避免打断组织与 consent 步骤。
 */
export function createSessionGuard(load: () => Promise<SessionState>) {
  return async (to: Pick<RouteLocationNormalized, 'path'>): Promise<GuardResult> => {
    const session = await load()
    if (!session.authenticated) {
      return to.path === '/login' ? true : { path: '/login' }
    }
    if (!session.organization) {
      return to.path === '/organizations' ? true : { path: '/organizations' }
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
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(createSessionGuard(getSession))
