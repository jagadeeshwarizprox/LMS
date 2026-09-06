import { useEffect, useState } from 'react'

/**
 * One calm entrance per view. The incoming content is pre-hidden, painted, then
 * revealed on the next frame, so a half built page is never on screen. This is
 * deliberately not a per card stagger: that reads as the app rebuilding itself.
 */
export default function ViewSwap({ swapKey, children }) {
  const [ready, setReady] = useState(false)

  useEffect(() => {
    setReady(false)
    const id = requestAnimationFrame(() => setReady(true))
    return () => cancelAnimationFrame(id)
  }, [swapKey])

  return <div className={`view-swap ${ready ? 'ready' : ''}`}>{children}</div>
}
