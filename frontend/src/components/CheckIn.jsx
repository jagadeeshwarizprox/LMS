import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import { useToast } from '../context/ToastContext'

/**
 * Check in, and an honest clock.
 *
 * Heartbeats only go out while the learner is actually doing something: a tab left
 * open in the background stops sending, and the server closes that session at its
 * last heartbeat. That is the whole reason the number is worth showing.
 */
export function useStudyClock(enabled) {
  const [state, setState] = useState(null)
  const timer = useRef(null)
  const active = useRef(true)

  const load = useCallback(async () => {
    if (!enabled) return
    try { setState(await api.get('/learner/study')) } catch { /* not a learner */ }
  }, [enabled])

  useEffect(() => { load() }, [load])

  /* only count time the learner is present for */
  useEffect(() => {
    const seen = () => { active.current = true }
    const gone = () => { active.current = document.visibilityState === 'visible' }
    document.addEventListener('visibilitychange', gone)
    window.addEventListener('mousemove', seen, { passive: true })
    window.addEventListener('keydown', seen)
    return () => {
      document.removeEventListener('visibilitychange', gone)
      window.removeEventListener('mousemove', seen)
      window.removeEventListener('keydown', seen)
    }
  }, [])

  useEffect(() => {
    if (!state?.checkedIn) { clearInterval(timer.current); return }
    timer.current = setInterval(async () => {
      if (!active.current || document.visibilityState !== 'visible') return
      active.current = false                       // must move again to count the next beat
      try {
        const chapterId = window.location.pathname.startsWith('/learn/chapter/')
          ? window.location.pathname.split('/').pop() : null
        const r = await api.post('/learner/study/heartbeat', { chapterId })
        setState((s) => (s ? { ...s, liveMinutes: r.minutes } : s))
      } catch { /* offline, the sweep will settle it */ }
    }, 120000)
    return () => clearInterval(timer.current)
  }, [state?.checkedIn])

  const checkIn = async (note) => setState(await api.post('/learner/study/check-in', { note }))
  const checkOut = async () => setState(await api.post('/learner/study/check-out', {}))

  return { state, checkIn, checkOut, reload: load }
}

export function hhmm(minutes = 0) {
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  return h ? `${h}h ${m}m` : `${m}m`
}

/** The topbar control. Small, always visible, never in the way. */
export default function CheckIn() {
  const { state, checkIn, checkOut } = useStudyClock(true)
  const toast = useToast()
  if (!state) return null

  const live = state.checkedIn ? state.todayMinutes : state.todayMinutes

  const start = async () => {
    await checkIn(null)
    toast.push('Checked in. Your time is counting.')
  }
  const stop = async () => {
    const r = await checkOut()
    toast.push(`Checked out. ${hhmm(r?.todayMinutes ?? state.todayMinutes)} today.`)
  }

  return (
    <div className="checkin">
      <button
        className={`checkin-btn ${state.checkedIn ? 'on' : ''}`}
        onClick={state.checkedIn ? stop : start}
        title={state.checkedIn ? 'Check out and settle your time' : 'Start counting your study time'}
      >
        <span className="checkin-dot" />
        {state.checkedIn ? 'Checked in' : 'Check in'}
      </button>
      <span className="checkin-time mono">{hhmm(live)}</span>
      {state.streakDays > 1 && (
        <span className="checkin-streak" title="Days in a row with study time">
          {state.streakDays} day streak
        </span>
      )}
    </div>
  )
}
