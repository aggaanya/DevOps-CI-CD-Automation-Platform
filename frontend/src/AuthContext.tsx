import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import type { User } from 'oidc-client-ts'
import { restoreSession, userManager } from './auth'

type AuthContextValue = {
  user: User | null
  isReady: boolean
  signOut: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [isReady, setIsReady] = useState(false)

  useEffect(() => {
    let cancelled = false
    restoreSession().then((u) => {
      if (cancelled) return
      setUser(u)
      setIsReady(true)
    })
    return () => { cancelled = true }
  }, [])

  const signOut = useCallback(async () => {
    await userManager.signoutRedirect({ post_logout_redirect_uri: window.location.origin })
  }, [])

  const value = useMemo(() => ({ user, isReady, signOut }), [user, isReady, signOut])

  if (!isReady) {
    return (
      <div className="auth-gate">
        <div className="loading">Starting secure session…</div>
      </div>
    )
  }

  return (
    <AuthContext.Provider value={value}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used within an AuthProvider')
  return context
}