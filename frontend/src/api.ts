import { freshAccessToken, silentlyRenew, userManager } from './auth'

type ApiErrorBody = { message?: string } | null

export async function api<T>(url: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  const token = await freshAccessToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let response = await fetch(url, { ...options, headers })

  if (response.status === 401) {
    const renewed = await silentlyRenew()
    if (renewed?.access_token) {
      headers.set('Authorization', `Bearer ${renewed.access_token}`)
      response = await fetch(url, { ...options, headers })
    }
    if (response.status === 401) {
      await userManager.signinRedirect()
      throw new Error('Session expired. Redirecting to sign in.')
    }
  }

  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as ApiErrorBody
    throw new Error(body?.message ?? `Request failed (${response.status})`)
  }

  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}