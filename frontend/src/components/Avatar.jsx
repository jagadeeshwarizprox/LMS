/**
 * A monogram, coloured from the person's own name.
 *
 * The same person is the same colour everywhere in the product, which is what makes
 * a list of thirty learners scannable instead of thirty rows of grey text. Colours
 * are drawn from the brand palette rather than generated, so nothing ever clashes.
 */

const PALETTE = [
  ['#002060', '#DCE7F7'], ['#008A9D', '#DBF0F3'], ['#0B2E72', '#DEE6F8'],
  ['#00609B', '#DAEBF6'], ['#4A2E83', '#E7E0F5'], ['#8A5A00', '#F7EBD5'],
  ['#0F6E4C', '#DCF0E7'], ['#8A2846', '#F7E0E7']
]

function pick(seed) {
  let h = 0
  for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) >>> 0
  return PALETTE[h % PALETTE.length]
}

export function initials(name = '') {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase()
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
}

export default function Avatar({ name = '', size = 34, track, onDark, className = '' }) {
  const [ink, wash] = pick(name || '?')
  return (
    <span
      className={`avatar ${className}`}
      style={{
        width: size,
        height: size,
        background: onDark ? 'rgba(255,255,255,.10)' : wash,
        color: onDark ? '#fff' : ink,
        fontSize: Math.max(10, size * 0.36),
        boxShadow: onDark ? 'inset 0 0 0 1px rgba(255,255,255,.14)' : `inset 0 0 0 1px ${ink}22`
      }}
      aria-hidden="true"
    >
      {initials(name)}
      {track && <span className={`avatar-track t-${track.toLowerCase()}`} title={track} />}
    </span>
  )
}
