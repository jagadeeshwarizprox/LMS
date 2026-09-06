import { useTheme } from '../context/ThemeContext'

/**
 * Two cuts of the same logo. The white plate is keyed out of both. The dark cut
 * lifts the wordmark but leaves the globe tile at #01153A, the exact navy of the
 * sidebar and splash, so the tile dissolves into the surface instead of sitting
 * on it as a box.
 */
export default function Brand({ onDark, className, style }) {
  const theme = useTheme()
  const dark = onDark ?? theme?.dark ?? false
  return (
    <img
      src={dark ? '/logo-on-dark.png' : '/logo-ink.png'}
      alt="ProITBridge"
      className={className}
      style={style}
    />
  )
}
