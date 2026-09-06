import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api/client'
import { useLearner } from '../../context/LearnerContext'
import SecurePlayer from '../../components/SecurePlayer'
import FaqBlock from '../../components/FaqBlock'
import Icon from '../../components/Icon'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, Page, Tag, fmtDate } from '../../components/Ui'

const KIND = {
  RECAP: { label: 'Recap', note: 'A subject gone over again, usually across a few days.' },
  DOUBT: { label: 'Doubt clearing', note: 'Open to every batch, so these are worth browsing.' },
  PROJECT: { label: 'Project sessions', note: '' },
  INDUSTRY: { label: 'Industry sessions', note: '' },
  INDUCTION: { label: 'Induction', note: '' },
  MOCK_DEBRIEF: { label: 'Mock debriefs', note: '' }
}

/**
 * The session library.
 *
 * This is not the course. The lectures live on their topics; this is what was
 * actually held live, which is a different thing to look for and grows every week.
 */
export default function Recordings({ embedded = false }) {
  const { learner } = useLearner()
  const [data, setData] = useState(null)
  const [playing, setPlaying] = useState(null)
  const [q, setQ] = useState('')
  const [kind, setKind] = useState('')

  useEffect(() => { api.get('/learner/recordings').then(setData).catch(() => setData(null)) }, [])


  /* the series card covers every part of a recap, so listing them again under their
     kind is the same three rows twice. They come back once the series card is hidden. */
  const showSeries = !kind && !q && Object.keys(data?.series || {}).length > 0


  const filtered = useMemo(() => {
    if (!data) return {}
    const match = (r) =>
      (!q || `${r.title} ${r.module || ''} ${r.coveredSummary || ''}`.toLowerCase()
        .includes(q.toLowerCase()))
    const out = {}
    Object.entries(data.byKind || {}).forEach(([k, rows]) => {
      if (kind && k !== kind) return
      const keep = rows
        .filter((r) => !(showSeries && r.seriesLabel))
        .filter(match)
      if (keep.length) out[k] = keep
    })
    return out
  }, [data, q, kind, showSeries])

  const row = (r) => (
    <div className="rec" key={r.id}>
      <button className="rec-head" onClick={() => setPlaying(playing === r.id ? null : r.id)}>
        <span className="rec-icon"><Icon name="recordings" size={16} /></span>
        <span className="rec-body">
          <strong>
            {r.title}
            {r.partNumber > 0 && (
              <span className="rec-part">
                Day {r.partNumber}{r.totalParts ? ` of ${r.totalParts}` : ''}
              </span>
            )}
          </strong>
          <span className="small muted d-block">
            {fmtDate(r.heldOn)}
            {r.module ? ` · ${r.module}` : ''}
            {r.durationMin ? ` · ${r.durationMin} min` : ''}
          </span>
        </span>
        <span className="rec-action">{playing === r.id ? 'Close' : 'Watch'}</span>
      </button>

      {playing === r.id && (
        <div className="rec-open">
          <SecurePlayer videoRef={r.videoRef} poster={r.title} />
          {r.coveredSummary && <p className="small mt-3 mb-0">{r.coveredSummary}</p>}
          {r.notes && <p className="small muted mt-2 mb-0">{r.notes}</p>}
          {r.questionsCovered?.length > 0 && (
            <div className="rec-questions">
              <div className="eyebrow mb-2">Questions this covered</div>
              <ul>{r.questionsCovered.map((x, i) => <li key={i}>{x}</li>)}</ul>
            </div>
          )}
        </div>
      )}
    </div>
  )

  if (!data) return <TableSkeleton />

  const seriesNames = Object.keys(data.series || {})

  /*
   * Rendered on its own page, and also as the second tab of Sessions.
   *
   * Recordings were a separate menu entry for a list that only makes sense next to the
   * sessions that produced it: a learner looking for last Thursday's doubt clearing was
   * as likely to start under Sessions as under Recorded sessions. Embedded, it drops its
   * own page heading and search box, because the tab it sits in already has them.
   */
  const Body = ({ children }) => embedded ? <>{children}</> : (
    <Page
      title="Recorded sessions"
      lede="Every session recording you have access to."
      actions={
        <input
          className="form-control"
          style={{ width: 240 }}
          placeholder="Search sessions"
          value={q}
          onChange={(e) => setQ(e.target.value)}
        />
      }
    >
      {children}
    </Page>
  )

  return (
    <Body>
      <div className="note mb-3">
        Your course videos live on each topic. These are the live sessions that were
        held: doubt clearing, recaps and the rest. Doubt clearing runs across every batch,
        so anything here is worth a look even if you were not in that room.
      </div>

      <div className="chips mb-3">
        <button className={`chip ${kind === '' ? 'on' : ''}`} onClick={() => setKind('')}>
          Everything
        </button>
        {Object.keys(data.byKind || {}).map((k) => (
          <button key={k} className={`chip ${kind === k ? 'on' : ''}`} onClick={() => setKind(k)}>
            {KIND[k]?.label || k}
          </button>
        ))}
      </div>

      {showSeries && (
        <Card title="Recap series" note="A subject gone over again, in order.">
          {seriesNames.map((name) => (
            <div className="series" key={name}>
              <div className="series-head">
                <strong>{name}</strong>
                <Tag kind="batch">{data.series[name].length} parts</Tag>
              </div>
              {data.series[name].map(row)}
            </div>
          ))}
        </Card>
      )}

      {Object.keys(filtered).length === 0 ? (
        <Empty title="Nothing here yet" icon="recordings">
          Sessions appear here once they have been held and published.
        </Empty>
      ) : Object.entries(filtered).map(([k, rows]) => (
        <Card key={k} title={KIND[k]?.label || k} note={KIND[k]?.note}>
          {rows.map(row)}
        </Card>
      ))}

      <FaqBlock placement="SESSIONS" track={learner?.trackType} />
    </Body>
  )
}
