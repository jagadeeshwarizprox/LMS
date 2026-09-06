import { useState } from 'react'
import Icon from './Icon'

/**
 * Paste anything, see what will be stored.
 *
 * The same parsing runs on the server, which is what actually decides. This exists so
 * somebody pasting a share link is told immediately that it worked, rather than saving
 * and hoping.
 */
const YOUTUBE = [
  /(?:youtube\.com|youtube-nocookie\.com)\/watch\?(?:.*&)?v=([\w-]{11})/,
  /youtu\.be\/([\w-]{11})/,
  /(?:youtube\.com|youtube-nocookie\.com)\/embed\/([\w-]{11})/,
  /youtube\.com\/shorts\/([\w-]{11})/,
  /youtube\.com\/live\/([\w-]{11})/,
  /^([\w-]{11})$/
]
const CLOUDFLARE = [
  /cloudflarestream\.com\/([0-9a-f]{32})/,
  /videodelivery\.net\/([0-9a-f]{32})/,
  /^([0-9a-f]{32})$/
]

export function parseVideo(input, provider) {
  if (!input || !input.trim()) return { id: null, note: null }
  let raw = input.trim()
  const iframe = raw.match(/src=["']([^"']+)["']/)
  if (iframe) raw = iframe[1]

  for (const p of (provider === 'CLOUDFLARE_STREAM' ? CLOUDFLARE : YOUTUBE)) {
    const m = raw.match(p)
    if (m) return { id: m[1], note: null }
  }
  if (raw.includes('list=') || raw.includes('/playlist')) {
    return { id: null, note: 'That is a playlist. Open the video itself and copy its link.' }
  }
  if (raw.includes('drive.google.com') || raw.includes('vimeo.com')) {
    return { id: null, note: 'Only YouTube and Cloudflare Stream play here.' }
  }
  return { id: null, note: 'That does not look like a video link yet.' }
}

export default function VideoField({
  value, onChange, provider = 'YOUTUBE', label = 'Video link', existingMasked
}) {
  const [touched, setTouched] = useState(false)
  const { id, note } = parseVideo(value, provider)

  return (
    <div className="videofield">
      <label className="form-label">{label}</label>
      <input
        className={`form-control ${touched && value && !id ? 'is-bad' : ''}`}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onBlur={() => setTouched(true)}
        placeholder={provider === 'CLOUDFLARE_STREAM'
          ? 'Paste the Cloudflare Stream link or uid'
          : 'Paste the YouTube link, for example https://youtu.be/dQw4w9WgXcQ'}
      />
      {id ? (
        <div className="videofield-ok">
          <Icon name="check" size={14} />
          Reads as <span className="mono">{id}</span>
          {existingMasked && existingMasked !== 'not set' && (
            <span className="muted"> · replaces {existingMasked}, every reference follows</span>
          )}
        </div>
      ) : value ? (
        <div className="videofield-bad">{note}</div>
      ) : (
        <div className="small muted mt-1">
          A watch link, a share link, or the embed snippet all work. Only the id is stored.
        </div>
      )}
    </div>
  )
}
