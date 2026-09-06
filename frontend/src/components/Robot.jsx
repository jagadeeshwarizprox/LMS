import { useEffect, useState } from 'react'

/**
 * The zPROx robot.
 *
 * Drawn rather than imported so it inherits the theme instead of fighting it: every
 * colour is a CSS variable, so it works on light and dark without a second asset and
 * stays sharp at any size. When you send real artwork this is the one file to replace,
 * and the three `mood` values are the contract it has to keep.
 *
 * It is used at reward moments and while waiting, never as decoration on a working
 * screen. A mascot that appears on every page stops being noticed within a day, and a
 * mascot that appears when something good happens does not.
 *
 * Motion is off for anyone who has asked their system for reduced motion. That is not a
 * nicety: for some people this kind of movement is genuinely unpleasant, and a robot is
 * not worth that.
 */

const MOODS = {
  idle: { brow: 0, mouth: 'M 40 62 Q 50 66 60 62', blink: true },
  happy: { brow: -2, mouth: 'M 38 60 Q 50 72 62 60', blink: true },
  thinking: { brow: 3, mouth: 'M 42 64 L 58 64', blink: false }
}

export default function Robot({ size = 120, mood = 'idle', className = '' }) {
  const [blinking, setBlinking] = useState(false)
  const [reduced, setReduced] = useState(false)

  useEffect(() => {
    const q = window.matchMedia?.('(prefers-reduced-motion: reduce)')
    if (!q) return
    const apply = () => setReduced(q.matches)
    apply()
    q.addEventListener?.('change', apply)
    return () => q.removeEventListener?.('change', apply)
  }, [])

  /* a blink at an uneven interval, because a metronome reads as a fault light */
  useEffect(() => {
    if (reduced || !MOODS[mood]?.blink) return
    let t
    const loop = () => {
      t = setTimeout(() => {
        setBlinking(true)
        setTimeout(() => setBlinking(false), 130)
        loop()
      }, 2600 + Math.random() * 2600)
    }
    loop()
    return () => clearTimeout(t)
  }, [mood, reduced])

  const m = MOODS[mood] || MOODS.idle

  return (
    <svg
      className={`zbot ${reduced ? '' : 'zbot-live'} ${className}`}
      width={size}
      height={size}
      viewBox="0 0 100 100"
      role="img"
      aria-label="zPROx assistant"
    >
      {/* the aerial, which is what carries the little sway */}
      <g className="zbot-ant">
        <line x1="50" y1="18" x2="50" y2="8" stroke="var(--blue-400)" strokeWidth="2.5"
          strokeLinecap="round" />
        <circle cx="50" cy="6" r="4" fill="var(--blue-500)" className="zbot-pulse" />
      </g>

      {/* head */}
      <rect x="20" y="18" width="60" height="52" rx="16"
        fill="var(--navy-800)" />
      <rect x="20" y="18" width="60" height="52" rx="16"
        fill="none" stroke="var(--navy-700)" strokeWidth="1" />

      {/* visor */}
      <rect x="27" y="27" width="46" height="26" rx="11" fill="var(--navy-900)" />

      {/* eyes */}
      <g className="zbot-eyes" transform={`translate(0 ${m.brow})`}>
        <ellipse cx="40" cy="40" rx="5" ry={blinking ? 0.7 : 5} fill="var(--blue-400)" />
        <ellipse cx="60" cy="40" rx="5" ry={blinking ? 0.7 : 5} fill="var(--blue-400)" />
      </g>

      {/* mouth */}
      <path d={m.mouth} stroke="var(--blue-500)" strokeWidth="2.5" fill="none"
        strokeLinecap="round" />

      {/* ears */}
      <rect x="14" y="34" width="6" height="16" rx="3" fill="var(--teal-600)" />
      <rect x="80" y="34" width="6" height="16" rx="3" fill="var(--teal-600)" />

      {/* body, cropped by the viewBox so it reads as a bust rather than a whole figure */}
      <rect x="30" y="74" width="40" height="24" rx="10" fill="var(--navy-700)" />
      <circle cx="50" cy="84" r="4.5" fill="var(--blue-500)" className="zbot-pulse" />
    </svg>
  )
}
