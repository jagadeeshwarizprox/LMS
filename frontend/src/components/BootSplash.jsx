import { useEffect, useRef, useState } from 'react'
import Brand from './Brand'

/* the node graph draws itself, then hands over to the wordmark */
const POINTS = [[60, 16], [97, 34], [104, 74], [76, 103], [36, 101], [14, 68], [20, 30], [60, 60]]
const EDGES = [[7, 0], [0, 1], [1, 2], [2, 3], [3, 4], [4, 5], [5, 6], [6, 7], [7, 1], [7, 3], [7, 5]]

const STAGES = ['Loading the console', 'Reading people and roles', 'Loading the catalogue', 'Ready']

/**
 * Shown once per session. It does not gate anything: if the app is ready early
 * the rail finishes early, and if it is slow the splash simply waits.
 */
export default function BootSplash({ onDone }) {
  const [named, setNamed] = useState(false)
  const [stage, setStage] = useState('')
  const [fill, setFill] = useState(0)
  const [leaving, setLeaving] = useState(false)
  const timers = useRef([])

  useEffect(() => {
    const at = (ms, fn) => timers.current.push(setTimeout(fn, ms))
    at(760, () => setNamed(true))
    STAGES.forEach((label, i) => at(820 + i * 260, () => {
      setStage(label)
      setFill(((i + 1) / STAGES.length) * 100)
    }))
    at(1900, () => setStage('Ready'))
    at(2050, () => setLeaving(true))
    at(2620, () => onDone?.())
    return () => timers.current.forEach(clearTimeout)
  }, [onDone])

  return (
    <div className={`splash ${leaving ? 'leaving' : ''}`} role="status" aria-label="Loading">
      <div className={`boot ${named ? 'named' : ''}`}>
        <div className="boot-mark">
          <svg viewBox="0 0 120 120" aria-hidden="true">
            <circle className="ring" cx="60" cy="60" r="46" />
            {EDGES.map((e, i) => (
              <line
                key={i}
                className="edge"
                x1={POINTS[e[0]][0]} y1={POINTS[e[0]][1]}
                x2={POINTS[e[1]][0]} y2={POINTS[e[1]][1]}
                style={{ animationDelay: `${i * 45}ms` }}
              />
            ))}
            {POINTS.map((p, i) => (
              <circle
                key={i}
                className="node"
                cx={p[0]} cy={p[1]} r={i === 7 ? 5 : 3.4}
                style={{ animationDelay: `${120 + i * 55}ms` }}
              />
            ))}
          </svg>
          <div className="boot-logo"><Brand onDark /></div>
        </div>
        <div className="boot-tagline">Learning console</div>
        <div className="boot-rail"><i style={{ width: `${fill}%` }} /></div>
        <div className="boot-stage">{stage}</div>
      </div>
    </div>
  )
}
