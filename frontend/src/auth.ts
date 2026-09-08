import { User, UserManager } from 'oidc-client-ts'
import type { UserManagerSettings } from 'oidc-client-ts'

const REALM = 'cicd-platform'
const CLIENT_ID = 'cicd-frontend'
const KEYCLOAK_AUTHORITY = `http://localhost:8083/realms/${REALM}`

const userManagerSettings: UserManagerSettings = {
  authority: KEYCLOAK_AUTHORITY,
  client_id: CLIENT_ID,
  redirect_uri: `${window.location.origin}/`,
  post_logout_redirect_uri: `${window.location.origin}/`,
  silent_redirect_uri: `${window.location.origin}/silent-renew.html`,
  scope: 'openid profile email',
  loadUserInfo: true,
  automaticSilentRenew: false,
}

export const userManager = new UserManager(userManagerSettings)

export function isOidcCallback(): boolean {
  const params = new URLSearchParams(window.location.search)
  return params.has('code') || params.has('state')
}

let renewing: Promise<User | null> | null = null

export function silentlyRenew(): Promise<User | null> {
  if (!renewing) {
    renewing = userManager.signinSilent()
      .catch(() => null)
      .finally(() => { renewing = null })
  }
  return renewing
}

export async function freshAccessToken(): Promise<string | null> {
  const user = await userManager.getUser()
  if (!user) return null
  if (user.expired) {
    const renewed = await silentlyRenew()
    return renewed?.access_token ?? null
  }
  return user.access_token
}

let sessionRestore: Promise<User | null> | null = null

export function restoreSession(): Promise<User | null> {
  if (!sessionRestore) {
    sessionRestore = (async () => {
      try {
        if (isOidcCallback()) {
          const user = await userManager.signinRedirectCallback()
          window.history.replaceState({}, document.title, window.location.pathname)
          return user
        }
        let user = await userManager.getUser()
        if (user?.expired) {
          user = await silentlyRenew()
        }
        if (!user) {
          await userManager.signinRedirect()
          return null
        }
        return user
      } catch (err) {
        console.error('Failed to restore OIDC session', err)
        return null
      }
    })()
  }
  return sessionRestore
}