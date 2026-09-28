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
  const [authError, setAuthError] = useState('')

  useEffect(() => {
    let cancelled = false
    restoreSession().then((result) => {
      if (cancelled) return
      if (result.status === 'authenticated') setUser(result.user)
      else if (result.status === 'error') setAuthError(result.message)
      setIsReady(true)
    })
    return () => { cancelled = true }
  }, [])

  const signOut = useCallback(async () => {
    await userManager.signoutRedirect({ post_logout_redirect_uri: `${window.location.origin}/` })
  }, [])

  const value = useMemo(() => ({ user, isReady, signOut }), [user, isReady, signOut])

  if (!isReady || !user) {
    return (
      <div className="auth-gate">
        <div className="loading">
          {authError ? (
            <>
              <span>{authError}</span>
              <button onClick={() => window.location.reload()}>Try again</button>
            </>
          ) : (
            'Starting secure session…'
          )}
        </div>
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