import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import { api } from '../api/client'
import { useAuth } from './AuthContext'

const LearnerContext = createContext(null)

/** Holds the learner dashboard payload: record, gates, features, roadmap, stats, batch. */
export function LearnerProvider({ children }) {
  const { user } = useAuth()
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  const load = useCallback(async () => {
    if (!user || user.role !== 'LEARNER') {
      setLoading(false)
      return
    }
    setLoading(true)
    try {
      /*
       * One request. The dashboard already returns continueWatching, so the extra call
       * that used to follow it was fetching the same thing twice and adding a second way
       * for the page to fail.
       */
      setData(await api.get('/learner/dashboard'))
      setError(null)
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }, [user])

  useEffect(() => { load() }, [load])

  const can = (key) => Boolean(data?.features?.[key])

  return (
    <LearnerContext.Provider value={{ ...(data || {}), raw: data, loading, error, reload: load, can }}>
      {children}
    </LearnerContext.Provider>
  )
}

export const useLearner = () => useContext(LearnerContext)
