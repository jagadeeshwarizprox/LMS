import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import { hhmm } from './CheckIn'

const KIND_LABEL = {
  CHAPTER: 'Next up', TASK: 'Sent back', SESSION: 'Session', CALL: 'Call', MOCK: 'Mock'
}

/**
 * One short answer to "what am I meant to do today". Self paced learners drift
 * because nothing ever tells them where to start, and a roadmap is not an answer
 * when it is forty chapters long.
 */
export default function ThisWeek() {
  const [data, setData] = useState(null)
  const navigate = useNavigate()

  useEffect(() => { api.get('/learner/this-week').then(setData).catch(() => setData(null)) }, [])
  if (!data) return null

  if (data.onHold) {
    return (
      <div className="week-card on-hold">
        <div className="eyebrow mb-1">On hold</div>
        <div className="week-headline">{data.headline}</div>
        {data.holdReason && <div className="small muted mt-1">{data.holdReason}</div>}
      </div>
    )
  }

  return (
    <div className="week-card">
      <div className="week-head">
        <div>
          <div className="eyebrow mb-1">Today</div>
          <div className="week-headline">{data.headline}</div>
        </div>
        <div className="week-meta">
          {data.weekMinutes > 0 && <span className="mono">{hhmm(data.weekMinutes)} this week</span>}
          {data.streakDays > 1 && <span className="week-streak">{data.streakDays} day streak</span>}
        </div>
      </div>

      {data.items?.length > 0 && (
        <div className="week-items">
          {data.items.map((it, i) => (
            <button key={i} className="week-item" onClick={() => navigate(it.to)}>
              <span className={`week-kind k-${it.kind.toLowerCase()}`}>{KIND_LABEL[it.kind] || it.kind}</span>
              <span className="week-title">{it.title}</span>
              <span className="week-detail">{it.detail}</span>
              <span className="week-cta">{it.cta}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
