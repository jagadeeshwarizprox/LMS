import { useLayoutEffect, useRef, useState } from 'react'

/**
 * Tabs with one underline that slides between them, rather than a border colour
 * flicking on and off. Measured after layout so it lands exactly under the label.
 */
export default function TabBar({ items, active, onChange }) {
  const wrap = useRef(null)
  const [ink, setInk] = useState({ x: 0, y: 0, w: 0, ready: false })

  /* tabs wrap on narrow screens, so the underline tracks both axes */
  useLayoutEffect(() => {
    const el = wrap.current?.querySelector('.step-tab.active')
    if (!el) return
    setInk({
      x: el.offsetLeft,
      y: el.offsetTop + el.offsetHeight - 2,
      w: el.offsetWidth,
      ready: true
    })
  }, [active, items])

  return (
    <div className="step-tabs" ref={wrap} role="tablist">
      {items.map((it) => (
        <button
          key={it.key}
          role="tab"
          aria-selected={active === it.key}
          className={`step-tab ${active === it.key ? 'active' : ''}`}
          onClick={() => onChange(it.key)}
          disabled={it.disabled}
        >
          {it.label}
          {it.done && <span style={{ color: 'var(--teal-600)' }}> &#10003;</span>}
        </button>
      ))}
      <span
        className="tab-ink"
        style={{
          width: ink.w,
          transform: `translate(${ink.x}px, ${ink.y}px)`,
          opacity: ink.ready ? 1 : 0
        }}
      />
    </div>
  )
}
