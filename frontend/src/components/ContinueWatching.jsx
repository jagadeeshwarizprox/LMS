import { useNavigate } from 'react-router-dom'
import Icon from './Icon'

/**
 * Started, not finished, most recent first.
 *
 * A two hour recap watched in three sittings is unusable without this, and finding
 * your place by dragging a scrubber is the small friction that quietly stops people
 * coming back.
 */
export default function ContinueWatching({ items = [] }) {
  const navigate = useNavigate()
  if (items.length === 0) return null

  return (
    <div className="cw">
      <div className="eyebrow mb-2">Continue watching</div>
      <div className="cw-row">
        {items.map((v) => (
          <button
            className="cw-card"
            key={v.videoRef}
            onClick={() => navigate(v.chapterId ? `/learn/chapter/${v.chapterId}` : '/learn/recordings')}
          >
            <span className="cw-play"><Icon name="recordings" size={16} /></span>
            <span className="cw-body">
              <strong>{v.title}</strong>
              <span className="cw-note">
                {v.label} in · {v.left} left
              </span>
            </span>
            <span className="cw-bar"><i style={{ width: `${v.percent}%` }} /></span>
          </button>
        ))}
      </div>
    </div>
  )
}
