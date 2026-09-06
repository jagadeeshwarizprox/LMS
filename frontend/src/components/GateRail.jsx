import { useEffect, useRef, useState } from 'react'

/**
 * The three onboarding gates drawn as one rail. When they are enforced nothing opens
 * until all three are cleared, so they are shown connected rather than as separate
 * checkboxes.
 * The teal fill travels along the connector when a gate is newly cleared, and
 * is set instantly on any later render so it does not replay on every paint.
 */
export default function GateRail({ gates, trackType }) {
  const seen = useRef({})
  const [fresh, setFresh] = useState({})

  const items = gates ? [
    { key: 'form', title: 'Information form', note: 'Eight sections about you' },
    { key: 'prereq', title: 'Prerequisite video', note: 'Baseline before module one' },
    {
      key: 'call',
      title: trackType === 'BATCH' ? 'Tuesday induction' : 'Onboarding call',
      note: trackType === 'BATCH' ? 'Attend or watch the recording' : 'Book a slot with your mentor'
    }
  ] : []

  useEffect(() => {
    if (!gates) return
    const newly = {}
    items.forEach((i) => {
      if (gates[i.key] && !seen.current[i.key]) newly[i.key] = true
      seen.current[i.key] = Boolean(gates[i.key])
    })
    if (Object.keys(newly).length) {
      setFresh(newly)
      const t = setTimeout(() => setFresh({}), 700)
      return () => clearTimeout(t)
    }
  }, [gates])

  if (!gates) return null

  const firstOpen = items.find((i) => !gates[i.key])?.key

  return (
    <div className="gate-rail" aria-label="Onboarding steps">
      {items.map((i) => {
        const done = Boolean(gates[i.key])
        const isFresh = Boolean(fresh[i.key])
        return (
          <div
            key={i.key}
            className={[
              'gate',
              done ? 'done' : '',
              firstOpen === i.key ? 'current' : '',
              isFresh ? 'just-cleared' : ''
            ].filter(Boolean).join(' ')}
          >
            <span className="gate-line">
              <i style={{
                transform: `scaleX(${done ? 1 : 0})`,
                transition: isFresh ? 'transform .55s .05s var(--ease)' : 'none'
              }} />
            </span>
            <span className="dot" />
            <div className="gate-title">{i.title}</div>
            <div className="gate-note">{done ? 'Cleared' : i.note}</div>
          </div>
        )
      })}
    </div>
  )
}
