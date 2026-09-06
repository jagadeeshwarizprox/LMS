import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import { api, setToken, getToken, deviceId, deviceLabel } from '../api/client'

const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null)
  const [ready, setReady] = useState(false)

  const refresh = useCallback(async () => {
    if (!getToken()) {
      setUser(null)
      setReady(true)
      return
    }
    try {
      const data = await api.get('/auth/me')
      setUser(data.user)
    } catch {
      setToken(null)
      setUser(null)
    } finally {
      setReady(true)
    }
  }, [])

  useEffect(() => {
    refresh()
    const out = () => setUser(null)
    window.addEventListener('pib:signed-out', out)
    return () => window.removeEventListener('pib:signed-out', out)
  }, [refresh])

  /* the portal is passed so the server can refuse a learner at the staff door and
     the other way round, after the password is checked rather than before */
  const signIn = async (email, password, portal) => {
    const data = await api.post('/auth/login', {
      email, password, portal, deviceId: deviceId(), deviceLabel: deviceLabel()
    })
    setToken(data.token)
    setUser(data.user)
    return data.user
  }

  const changePassword = async (currentPassword, newPassword) => {
    const data = await api.post('/auth/change-password', { currentPassword, newPassword })
    setToken(data.token)
    setUser(data.user)
    return data.user
  }

  const signOut = async () => {
    try { await api.post('/auth/logout', {}) } catch { /* already gone */ }
    setToken(null)
    setUser(null)
  }

  return (
    <AuthContext.Provider value={{ user, ready, signIn, signOut, changePassword, refresh, setUser }}>
      {children}
    </AuthContext.Provider>
  )
}

export const useAuth = () => useContext(AuthContext)
