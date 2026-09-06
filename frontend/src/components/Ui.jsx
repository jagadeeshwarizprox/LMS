import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import Icon from './Icon'

/**
 * The page header. The section name is not repeated here: the topbar crumb already
 * carries it, so this is the title, the line that explains it, and the actions.
 */
export function Page({ title, lede, actions, children }) {
  return (
    <div className="page">
      {title && (
        <div className="pagehead">
          <div>
            {title && <h1>{title}</h1>}
            {lede && <div className="lede">{lede}</div>}
          </div>
          {actions && <div className="acts">{actions}</div>}
        </div>
      )}
      {children}
    </div>
  )
}

export function Card({ title, eyebrow, note, actions, className = '', children }) {
  return (
    <section className={`card-pib ${className}`}>
      {(title || eyebrow || actions) && (
        <div className="card-head">
          <div>
            {eyebrow && <div className="eyebrow" style={{ marginBottom: 4 }}>{eyebrow}</div>}
            {title && <h3>{title}</h3>}
            {note && <div className="tiny muted">{note}</div>}
          </div>
          {actions && <div className="acts">{actions}</div>}
        </div>
      )}
      {children}
    </section>
  )
}

/** A row of cards with no gutter fiddling at the call site. */
export function Grid({ cols = 3, children, className = '', style }) {
  return (
    <div className={`grid g${cols} ${className}`} style={style}>{children}</div>
  )
}

/** The strip of "is this ready" ticks the console opens most pages with. */
export function Ready({ items }) {
  return (
    <div className="ready">
      {items.map(([label, detail, ok]) => (
        <div className="ready-c" key={label}>
          <span className={`rmark ${ok ? '' : 'no'}`}>
            {ok ? <Icon name="check" size={12} strokeWidth={3} /> : '!'}
          </span>
          <div><strong>{label}</strong><span>{detail}</span></div>
        </div>
      ))}
    </div>
  )
}

/** Underlined tabs with a sliding ink bar, used wherever a page has sections. */
export function Tabs({ tabs, value, onChange }) {
  const wrap = useRef(null)
  const [ink, setInk] = useState({ width: 0, left: 0 })

  useEffect(() => {
    const on = wrap.current?.querySelector('.tab.on')
    if (on) setInk({ width: on.offsetWidth, left: on.offsetLeft })
  }, [value, tabs.length])

  return (
    <div className="tabs" ref={wrap}>
      {tabs.map((t, i) => (
        <button
          key={t}
          className={`tab ${value === i ? 'on' : ''}`}
          onClick={() => onChange(i)}
        >
          {t}
        </button>
      ))}
      <span className="tabink" style={{ width: ink.width, transform: `translateX(${ink.left}px)` }} />
    </div>
  )
}

/** The console switch: a track and a knob, never a checkbox. */
export function Switch({ checked, onChange, label, disabled }) {
  return (
    <label className="sw">
      <input
        type="checkbox"
        checked={!!checked}
        disabled={disabled}
        onChange={(e) => onChange?.(e.target.checked)}
      />
      <i />
      {label && <span>{label}</span>}
    </label>
  )
}

export function Note({ children }) {
  return <div className="note">{children}</div>
}

/** A single figure as a proportion, drawn as a ring rather than a bar. */
export function Ring({ pct, size = 86, label = 'done' }) {
  const r = (size - 10) / 2
  const c = 2 * Math.PI * r
  const [offset, setOffset] = useState(c)
  useEffect(() => {
    const raf = requestAnimationFrame(() => setOffset(c * (1 - pct / 100)))
    return () => cancelAnimationFrame(raf)
  }, [pct, c])
  return (
    <div className="ring" style={{ width: size, height: size }}>
      <svg width={size} height={size}>
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--line)" strokeWidth="7" />
        <circle
          className="fill" cx={size / 2} cy={size / 2} r={r} fill="none"
          stroke="var(--blue-500)" strokeWidth="7" strokeLinecap="round"
          strokeDasharray={c} strokeDashoffset={offset}
        />
      </svg>
      <div className="ring-l"><strong>{pct}%</strong><span>{label}</span></div>
    </div>
  )
}

/** One bar split by category, for a population that adds up to a whole. */
export function Band({ segments }) {
  const total = segments.reduce((a, s) => a + s[1], 0) || 1
  const [on, setOn] = useState(false)
  useEffect(() => {
    const raf = requestAnimationFrame(() => setOn(true))
    return () => cancelAnimationFrame(raf)
  }, [])
  return (
    <>
      <div className="band">
        {segments.map(([label, value, colour]) => (
          <i key={label} style={{ width: on ? `${(value / total) * 100}%` : 0, background: colour }} />
        ))}
      </div>
      <div className="bandkeys">
        {segments.map(([label, value, colour]) => (
          <span className="bandkey" key={label}>
            <i style={{ background: colour }} />{label} <strong>{value}</strong>
          </span>
        ))}
      </div>
    </>
  )
}

/** A column chart small enough to sit inside a card without a library. */
export function Bars({ values, labels, altLast }) {
  const max = Math.max(...values, 1)
  return (
    <>
      <div className="bars">
        {values.map((v, i) => (
          <div className={`bcol ${altLast && i === values.length - 1 ? 'alt' : ''}`} key={i}>
            <div
              className="bbar"
              style={{ height: `${Math.round((v / max) * 100)}%`, animationDelay: `${i * 55}ms` }}
            />
          </div>
        ))}
      </div>
      <div style={{ display: 'flex', gap: 6 }}>
        {labels.map((l, i) => <div className="blab" style={{ flex: '1 1 0' }} key={i}>{l}</div>)}
      </div>
    </>
  )
}

/** A load or completion figure, as a short track with the number beside it. */
export function Pct({ value }) {
  const [w, setW] = useState(0)
  useEffect(() => {
    const raf = requestAnimationFrame(() => setW(value))
    return () => cancelAnimationFrame(raf)
  }, [value])
  return (
    <div className="pct">
      <span className="pct-t"><i style={{ width: `${w}%` }} /></span>
      <span className="mono tiny">{value}%</span>
    </div>
  )
}

/**
 * A number that arrives rather than appears. It counts from the value it last held,
 * so a dashboard refresh reads as a change instead of a repaint. Anything that is
 * not a plain number (a duration, a percentage with a suffix) is left alone.
 */
function useCountUp(value) {
  const numeric = typeof value === 'number'
    ? value
    : /^-?\d+%?$/.test(String(value)) ? parseInt(String(value), 10) : null
  const suffix = typeof value === 'string' && value.endsWith('%') ? '%' : ''
  const from = useRef(numeric ?? 0)
  const [shown, setShown] = useState(numeric ?? 0)

  useEffect(() => {
    if (numeric === null) return
    const start = from.current
    if (start === numeric) { setShown(numeric); return }
    const t0 = performance.now()
    const dur = 620
    let raf
    const step = (t) => {
      const p = Math.min(1, (t - t0) / dur)
      const eased = 1 - Math.pow(1 - p, 3)
      setShown(Math.round(start + (numeric - start) * eased))
      if (p < 1) raf = requestAnimationFrame(step)
      else from.current = numeric
    }
    raf = requestAnimationFrame(step)
    return () => cancelAnimationFrame(raf)
  }, [numeric])

  return numeric === null ? value : `${shown}${suffix}`
}

export function Stat({ value, label, tone, icon, hint, trend }) {
  const shown = useCountUp(value)
  return (
    <div className="stat">
      {icon && <span className="stat-icon"><Icon name={icon} size={16} /></span>}
      <div className="value" style={tone ? { color: tone } : undefined}>{shown}</div>
      <div className="label">{label}</div>
      {hint && (
        <div className="stat-hint">
          {trend && <span className={`trend ${trend[0]}`}>{trend[1]} </span>}
          {hint}
        </div>
      )}
    </div>
  )
}

/** A label that appears on hover and on focus, so it works without a mouse. */
export function Tip({ text, children }) {
  return (
    <span className="tip" tabIndex={0}>
      {children}
      <span className="tip-body" role="tooltip">{text}</span>
    </span>
  )
}

export function Empty({ title, children, action, icon = 'check' }) {
  return (
    <div className="empty">
      <span className="empty-mark"><Icon name={icon} size={20} /></span>
      <h4>{title}</h4>
      {children && <p className="mb-2" style={{ fontSize: '.88rem' }}>{children}</p>}
      {action}
    </div>
  )
}

/**
 * What a page shows when its own data did not arrive. The message is the one the server
 * actually sent, because "something went wrong" tells nobody anything, and the retry is
 * there because most of these are a dropped connection rather than a real fault.
 */
export function LoadError({ error, onRetry, title = 'That did not load' }) {
  return (
    <Empty title={title} icon="risk" action={
      onRetry ? <button className="btn btn-pib" onClick={onRetry}>Try again</button> : null
    }>
      {error}
    </Empty>
  )
}

export function Loading({ label = 'Loading' }) {
  return (
    <div className="d-flex align-items-center gap-2 text-muted py-4" style={{ fontSize: '.9rem' }}>
      <span className="spinner-border spinner-border-sm" role="status" aria-hidden="true" />
      {label}
    </div>
  )
}

export function Tag({ kind = 'ok', title, children }) {
  return <span className={`tag tag-${kind}`} title={title}>{children}</span>
}

export function TrackTag({ type }) {
  return type === 'PREMIUM'
    ? <Tag kind="premium">Premium</Tag>
    : <Tag kind="batch">Batch</Tag>
}

export function StatusTag({ status }) {
  const map = {
    APPROVED: ['ok', 'Approved'],
    SUBMITTED: ['wait', 'Waiting on review'],
    CHANGES: ['stop', 'Changes asked'],
    NOT_STARTED: ['batch', 'Not started'],
    PENDING: ['wait', 'Pending'],
    SCHEDULED: ['batch', 'Scheduled'],
    DONE: ['ok', 'Done']
  }
  const [kind, label] = map[status] || ['batch', status]
  return <Tag kind={kind}>{label}</Tag>
}

/**
 * Progress eases from wherever it was to wherever it is now, never from zero on
 * a re-render. The starting point is remembered per bar id.
 */
const barMemory = new Map()

export function Bar({ done, total, id }) {
  const pct = total ? Math.round((done / total) * 100) : 0
  const key = id ?? `${done}/${total}`
  const fill = useRef(null)
  const [width, setWidth] = useState(() => barMemory.get(key) ?? 0)

  useEffect(() => {
    const from = barMemory.get(key) ?? 0
    setWidth(from)
    const raf = requestAnimationFrame(() => {
      setWidth(pct)
      barMemory.set(key, pct)
    })
    return () => cancelAnimationFrame(raf)
  }, [key, pct])

  return (
    <div className="d-flex align-items-center gap-2">
      <div className={`bar ${pct === 100 ? 'done' : ''}`} style={{ width: 110 }}>
        <i ref={fill} style={{ width: `${width}%`, transition: 'width .7s var(--ease)' }} />
      </div>
      <span className="mono" style={{ fontSize: '.74rem', color: 'var(--ink-60)' }}>{done}/{total}</span>
    </div>
  )
}

/** A step pip that pops the moment it is earned, and stays quiet after that. */
export function Pip({ state, id, title }) {
  const seen = useRef(null)
  const [fresh, setFresh] = useState(false)

  useEffect(() => {
    if (seen.current !== null && seen.current !== 'on' && state === 'on') {
      setFresh(true)
      const t = setTimeout(() => setFresh(false), 600)
      seen.current = state
      return () => clearTimeout(t)
    }
    seen.current = state
  }, [state, id])

  return (
    <span
      className={`pip ${state === 'on' ? 'on' : state === 'part' ? 'part' : ''} ${fresh ? 'just-earned' : ''}`}
      title={title}
    />
  )
}

export function LinkButton({ to, children, className = 'btn-quiet' }) {
  return <Link to={to} className={`btn ${className}`}>{children}</Link>
}

export function fmtDate(value) {
  if (!value) return '\u2014'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  return d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })
}

export function fmtDateTime(value) {
  if (!value) return '\u2014'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  return d.toLocaleString('en-IN', {
    day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit'
  })
}
