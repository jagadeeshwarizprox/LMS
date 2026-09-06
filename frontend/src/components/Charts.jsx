/**
 * One chart language, drawn in SVG.
 *
 * No library: every chart here is a handful of shapes, and a dependency that ships
 * a hundred kilobytes to draw five bars is not a trade worth making. They share the
 * same palette, the same rounding and the same idea of an empty state.
 */

/** Progress as a ring. Draws itself on first paint, then eases between values. */
export function Ring({ value = 0, size = 84, stroke = 8, label, tone }) {
  const r = (size - stroke) / 2
  const c = 2 * Math.PI * r
  const pct = Math.max(0, Math.min(100, value))
  return (
    <div className="ring" style={{ width: size, height: size }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
        <circle
          cx={size / 2} cy={size / 2} r={r}
          fill="none" stroke="var(--line)" strokeWidth={stroke}
        />
        <circle
          className="ring-fill"
          cx={size / 2} cy={size / 2} r={r}
          fill="none"
          stroke={tone || 'var(--blue-500)'}
          strokeWidth={stroke}
          strokeLinecap="round"
          strokeDasharray={c}
          strokeDashoffset={c - (pct / 100) * c}
          transform={`rotate(-90 ${size / 2} ${size / 2})`}
        />
      </svg>
      <div className="ring-label">
        <strong>{Math.round(pct)}%</strong>
        {label && <span>{label}</span>}
      </div>
    </div>
  )
}

/** A run of days or weeks. Bars, not a line: discrete things deserve discrete marks. */
export function Bars({ data = [], height = 96, tone, valueKey = 'minutes', labelKey = 'day', format }) {
  const peak = Math.max(1, ...data.map((d) => d[valueKey] || 0))
  return (
    <div className="bars" style={{ height }}>
      {data.map((d, i) => {
        const v = d[valueKey] || 0
        return (
          <div
            className="bars-col"
            key={i}
            title={`${d[labelKey]}: ${format ? format(v) : v}`}
          >
            <div
              className="bars-bar"
              style={{
                height: `${Math.max(2, (v / peak) * 100)}%`,
                background: tone || undefined,
                animationDelay: `${i * 18}ms`
              }}
            />
            <span className="bars-label">{String(d[labelKey]).slice(-2)}</span>
          </div>
        )
      })}
    </div>
  )
}

/** A distribution across bands, read left to right. */
export function BandBar({ data = [], tone = 'var(--blue-500)' }) {
  const total = data.reduce((a, d) => a + (d.count || 0), 0)
  if (total === 0) return <div className="small muted">Nothing to show yet.</div>
  return (
    <div className="bandbar">
      <div className="bandbar-track">
        {data.map((d, i) => (
          <span
            key={i}
            className="bandbar-seg"
            style={{
              width: `${(d.count / total) * 100}%`,
              background: `color-mix(in srgb, ${tone} ${30 + i * 17}%, transparent)`
            }}
            title={`${d.band}: ${d.count}`}
          />
        ))}
      </div>
      <div className="bandbar-keys">
        {data.map((d, i) => (
          <span key={i} className="bandbar-key">
            <i style={{ background: `color-mix(in srgb, ${tone} ${30 + i * 17}%, transparent)` }} />
            {d.band} <strong>{d.count}</strong>
          </span>
        ))}
      </div>
    </div>
  )
}

/** Two numbers with a direction, which is usually all a trend needs to say. */
export function Trend({ value, previous, format = (v) => v, label }) {
  /* an arrow against an identical or absent previous value says nothing, so drop it */
  const comparable = previous !== undefined && previous !== null && previous !== value
  const up = value >= previous
  const delta = comparable && previous !== 0
    ? Math.round(((value - previous) / previous) * 100) : null
  return (
    <div className="trend">
      <strong>{format(value)}</strong>
      {comparable && (
        <span className={`trend-arrow ${up ? 'up' : 'down'}`}>
          {up ? '\u2191' : '\u2193'}{delta === null ? '' : ` ${Math.abs(delta)}%`}
        </span>
      )}
      {label && <span className="trend-label">{label}</span>}
    </div>
  )
}
