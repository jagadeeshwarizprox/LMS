import { createContext, useCallback, useContext, useRef, useState } from 'react'

const ToastContext = createContext(null)

/**
 * Toasts, and undo.
 *
 * Undo is worth more than a confirmation dialog for anything reversible. A dialog asks
 * every single time, including the ninety-nine times the person was right, and it trains
 * them to click through without reading. Undo costs nothing when you meant it and saves
 * you when you did not.
 *
 * The action only actually runs when the toast expires, so the common case is one
 * request rather than two.
 */
export function ToastProvider({ children }) {
  const [items, setItems] = useState([])
  const timers = useRef({})

  const dismiss = useCallback((id) => {
    setItems((list) => list.map((t) => (t.id === id ? { ...t, shown: false } : t)))
    setTimeout(() => setItems((list) => list.filter((t) => t.id !== id)), 340)
  }, [])

  const push = useCallback((message, tone = 'good') => {
    const id = Math.random().toString(36).slice(2)
    setItems((list) => [...list, { id, message, tone, shown: false }])
    /* paint it off screen first, then slide it in on the next frame */
    requestAnimationFrame(() => {
      setItems((list) => list.map((t) => (t.id === id ? { ...t, shown: true } : t)))
    })
    timers.current[id] = setTimeout(() => dismiss(id), 4000)
    return id
  }, [dismiss])

  /**
   * Show the result immediately, run the real work when the window closes.
   *
   * `commit` is what happens if they leave it alone; `revert` puts the screen back if
   * they press undo. Ten seconds, because four is not enough to notice a mistake and
   * twenty leaves the screen cluttered.
   */
  const pushUndo = useCallback(({ message, commit, revert, seconds = 10 }) => {
    const id = Math.random().toString(36).slice(2)
    setItems((list) => [...list, { id, message, tone: 'undo', shown: false, undo: true }])
    requestAnimationFrame(() => {
      setItems((list) => list.map((t) => (t.id === id ? { ...t, shown: true } : t)))
    })

    timers.current[id] = setTimeout(async () => {
      dismiss(id)
      try {
        await commit?.()
      } catch (e) {
        /* the work failed after the window closed, so say so and put it back */
        push(e.message || 'That did not go through.', 'bad')
        revert?.()
      }
    }, seconds * 1000)

    const undo = () => {
      clearTimeout(timers.current[id])
      dismiss(id)
      revert?.()
    }
    setItems((list) => list.map((t) => (t.id === id ? { ...t, undo } : t)))
    return id
  }, [dismiss, push])

  return (
    <ToastContext.Provider value={{ push, pushUndo }}>
      {children}
      <div className="toast-stack">
        {items.map((t) => (
          <div
            key={t.id}
            className={`toast-pib ${t.tone === 'bad' ? 'bad' : ''} ${t.tone === 'undo' ? 'undo' : ''} ${t.shown ? 'shown' : ''}`}
            role="status"
          >
            <span>{t.message}</span>
            {typeof t.undo === 'function' && (
              <button className="toast-undo" onClick={t.undo}>Undo</button>
            )}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

export const useToast = () => useContext(ToastContext)
