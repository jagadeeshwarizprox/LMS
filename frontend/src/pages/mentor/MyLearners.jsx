import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { api } from '../../api/client'
import Avatar from '../../components/Avatar'
import Icon from '../../components/Icon'
import Drawer from '../../components/Drawer'
import { BandBar, Ring, Bars } from '../../components/Charts'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, LoadError, Page, Stat, Tag, fmtDate, fmtDateTime } from '../../components/Ui'

const VIEWS = [
  ['all', 'Everyone'],
  ['waiting', 'Waiting on me'],
  ['risk', 'At risk'],
  ['quiet', 'Quiet this week'],
  ['onboarding', 'Still onboarding']
]

const SORTS = [
  ['name', 'Name'],
  ['percent', 'Progress'],
  ['idle', 'Last seen'],
  ['waiting', 'Waiting on me'],
  ['study', 'Study time']
]

function hhmm(m = 0) {
  const h = Math.floor(m / 60)
  return h ? `${h}h ${m % 60}m` : `${m}m`
}

/**
 * A mentor thinks in cohorts, not in a flat list. Learners arrive grouped by batch
 * with premium as its own group, and every row carries the two facts that decide who
 * to contact today: when they last did anything, and what is waiting on whom.
 */
export default function MyLearners() {
  const [data, setData] = useState(null)
  const [q, setQ] = useState('')
  /*
   * The desk tiles and the premium door link here with a filter in the address, and this
   * page ignored it: every one of them landed on the same unfiltered list, so a mentor
   * clicking "at risk" got everybody and had to find the six themselves.
   */
  const [params, setParams] = useSearchParams()
  const [view, setView] = useState(params.get('filter') || 'all')
  const [track, setTrack] = useState(params.get('track') || 'ALL')
  const [sort, setSort] = useState('name')
  const [collapsed, setCollapsed] = useState({})
  const [open, setOpen] = useState(null)
  const [detail, setDetail] = useState(null)
  const navigate = useNavigate()

  const [error, setError] = useState(null)

  const load = () => api.get('/mentor/roster')
    .then((r) => { setData(r); setError(null) })
    .catch((e) => setError(e.message))

  useEffect(() => { load() }, [])

  useEffect(() => {
    const next = {}
    if (view !== 'all') next.filter = view
    if (track !== 'ALL') next.track = track
    setParams(next, { replace: true })
  }, [view, track])

  useEffect(() => {
    if (!open) { setDetail(null); return }
    api.get(`/mentor/learners/${open.learnerId}/study-time`).then(setDetail).catch(() => setDetail(null))
  }, [open])

  const groups = useMemo(() => {
    if (!data) return []
    const match = (l) => {
      if (q && !`${l.name} ${l.email}`.toLowerCase().includes(q.toLowerCase())) return false
      if (track !== 'ALL' && l.trackType !== track) return false
      if (view === 'waiting') return l.waitingOnMe > 0
      if (view === 'risk') return l.atRisk
      if (view === 'quiet') return l.studyMinutesWeek === 0 && !l.onHold
      if (view === 'onboarding') return !l.onboarded
      return true
    }
    const cmp = {
      name: (a, b) => a.name.localeCompare(b.name),
      percent: (a, b) => b.percent - a.percent,
      idle: (a, b) => (b.idleDays ?? 999) - (a.idleDays ?? 999),
      waiting: (a, b) => b.waitingOnMe - a.waitingOnMe,
      study: (a, b) => b.studyMinutesWeek - a.studyMinutesWeek
    }[sort]
    return data.groups
      .map((g) => ({ ...g, learners: g.learners.filter(match).sort(cmp) }))
      .filter((g) => g.learners.length > 0)
  }, [data, q, view, sort, track])

  if (error) return <Page title="My learners"><LoadError error={error} onRetry={load} /></Page>
  if (!data) return <TableSkeleton />

  const shown = groups.reduce((n, g) => n + g.learners.length, 0)

  return (
    <Page
      title="My learners"
      lede="Everyone assigned to you, premium and batch."
      actions={
        <input
          className="form-control"
          style={{ width: 240 }}
          placeholder="Search by name or mail"
          value={q}
          onChange={(e) => setQ(e.target.value)}
        />
      }
    >
      {data.cover?.covering?.length > 0 && (
        <div className="note mb-3">
          You are covering for {data.cover.covering.map((c) => c.mentor).join(' and ')} until{' '}
          {data.cover.covering[0].until}. Their learners appear below and stay theirs; anything
          you do is recorded under your name.
        </div>
      )}
      {data.cover?.coveredBy?.length > 0 && (
        <div className="note mb-3">
          {data.cover.coveredBy.map((c) => c.mentor).join(' and ')} is covering your learners
          until {data.cover.coveredBy[0].until}.
        </div>
      )}

      <div className="row g-3 mb-4">
        <div className="col-6 col-lg-3">
          <Stat value={data.total} label="Learners" icon="batch" />
        </div>
        <div className="col-6 col-lg-3">
          <Stat value={data.counts.waitingOnMe} label="Items waiting on me" icon="queues"
            tone={data.counts.waitingOnMe ? 'var(--warn)' : undefined} />
        </div>
        <div className="col-6 col-lg-3">
          <Stat value={data.counts.atRisk} label="At risk" icon="risk"
            tone={data.counts.atRisk ? 'var(--stop)' : undefined} />
        </div>
        <div className="col-6 col-lg-3">
          <Stat value={data.counts.quietThisWeek} label="Quiet this week" icon="time" />
        </div>
      </div>

      <Card title="Where my learners are" note="Progress spread across everyone assigned to me.">
        <BandBar data={data.distribution} />
      </Card>

      <div className="filterbar">
        <div className="chips">
          {VIEWS.map(([k, label]) => (
            <button key={k} className={`chip ${view === k ? 'on' : ''}`} onClick={() => setView(k)}>
              {label}
            </button>
          ))}
          <span className="chip-sep" aria-hidden="true" />
          {[['ALL', 'Both tracks'], ['PREMIUM', 'Premium'], ['BATCH', 'Batch']].map(([k, label]) => (
            <button key={k} className={`chip ${track === k ? 'on' : ''}`} onClick={() => setTrack(k)}>
              {label}
            </button>
          ))}
        </div>
        <label className="sortby">
          Sort by
          <select className="form-select" value={sort} onChange={(e) => setSort(e.target.value)}>
            {SORTS.map(([k, l]) => <option key={k} value={k}>{l}</option>)}
          </select>
        </label>
      </div>

      {shown === 0 ? (
        <Empty title="Nobody matches that" icon="search">
          Clear the filter or the search to see everyone again.
        </Empty>
      ) : groups.map((g) => (
        <div className="cohort" key={g.label}>
          <button
            className="cohort-head"
            onClick={() => setCollapsed((c) => ({ ...c, [g.label]: !c[g.label] }))}
          >
            <Icon name={collapsed[g.label] ? 'expand' : 'collapse'} size={15} />
            <span className="cohort-name">{g.label}</span>
            <span className="cohort-count">{g.learners.length}</span>
            <span className="cohort-meta">
              {g.waitingOnMe > 0 && <Tag kind="wait">{g.waitingOnMe} waiting</Tag>}
              {g.atRisk > 0 && <Tag kind="stop">{g.atRisk} at risk</Tag>}
              <span className="mono small muted">{g.averagePercent}% average</span>
            </span>
          </button>

          {!collapsed[g.label] && (
            <table className="table table-pib mb-0">
              <thead>
                <tr>
                  <th>Learner</th><th>Progress</th><th>Videos</th><th>Study, this week</th>
                  <th>Last seen</th><th>Last submission</th><th>Waiting on me</th><th />
                </tr>
              </thead>
              <tbody>
                {g.learners.map((l) => (
                  <tr key={l.learnerId} className={l.atRisk ? 'row-risk' : ''}>
                    <td>
                      <button className="who-cell as-link" onClick={() => setOpen(l)}>
                        <Avatar name={l.name} size={34} track={l.trackType} />
                        <span>
                          <span className="who-name">{l.name}</span>
                          <span className="who-sub mono">{l.email}</span>
                        </span>
                      </button>
                    </td>
                    <td>
                      <div className="pct">
                        <div className="pct-track"><i style={{ width: `${l.percent}%` }} /></div>
                        <span className="mono">{l.percent}%</span>
                      </div>
                    </td>
                    {/*
                      * Chapters ticked and videos watched are not the same number, and
                      * the gap between them is the useful part: three started and
                      * abandoned is a different conversation from three never opened.
                      */}
                    <td className="mono">
                      {l.videosFinished == null ? '\u2014' : (
                        <>
                          {l.videosFinished} watched
                          {l.videosStarted > 0 && (
                            <span className="learn-part"> · {l.videosStarted} part done</span>
                          )}
                          <span className="sub">
                            {l.daysSinceWatched == null ? 'never watched'
                              : l.daysSinceWatched === 0 ? 'watched today'
                              : `last ${l.daysSinceWatched}d ago`}
                          </span>
                        </>
                      )}
                    </td>
                    <td className="mono">
                      {l.studyMinutesWeek === 0
                        ? <span className="muted">nothing</span>
                        : hhmm(l.studyMinutesWeek)}
                    </td>
                    <td className="mono">
                      {l.idleDays === null
                        ? <Tag kind="stop">never</Tag>
                        : l.idleDays >= 7
                          ? <Tag kind="stop">{l.idleDays}d ago</Tag>
                          : `${l.idleDays}d ago`}
                    </td>
                    <td className="mono">{l.lastSubmission ? fmtDate(l.lastSubmission) : '\u2014'}</td>
                    <td>
                      {l.waitingOnMe > 0
                        ? <Tag kind="wait">{l.waitingOnMe}</Tag>
                        : <span className="muted">clear</span>}
                    </td>
                    <td className="text-end">
                      {l.onHold && <Tag kind="batch">On hold</Tag>}
                      <button className="btn btn-quiet ms-2"
                        onClick={() => navigate(`/mentor/learners/${l.learnerId}`)}>
                        Open
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      ))}

      <Drawer
        open={Boolean(open)}
        title={open?.name || ''}
        subtitle={open ? `${open.trackType === 'PREMIUM' ? 'Premium' : 'Batch ' + (open.batch || '')} \u00b7 ${open.bundle || 'No course'}` : ''}
        onClose={() => setOpen(null)}
        footer={open && (
          <>
            <button className="btn btn-quiet" onClick={() => setOpen(null)}>Close</button>
            <button className="btn btn-pib"
              onClick={() => navigate(`/mentor/learners/${open.learnerId}`)}>
              Open full record
            </button>
          </>
        )}
      >
        {open && (
          <>
            <div className="drw-identity">
              <Avatar name={open.name} size={54} track={open.trackType} />
              <div>
                <div className="mono small muted">{open.email}</div>
                {open.phone && <div className="mono small muted">{open.phone}</div>}
              </div>
              <Ring value={open.percent} size={72} label="course" />
            </div>

            <div className="row g-2 mb-3">
              <div className="col-6"><Stat value={open.avgQuiz} label="Average test" /></div>
              <div className="col-6">
                <Stat value={hhmm(open.studyMinutesWeek)} label="Study this week" />
              </div>
            </div>

            {detail && (
              <Card title="Effort, last two weeks"
                note={detail.trend === 'UP'
                  ? 'Up on last week. Minutes rise before results do.'
                  : 'Down on last week. Minutes fall before tasks do.'}>
                <Bars data={detail.last14} height={90} format={hhmm} />
              </Card>
            )}

            <Card title="Where things stand">
              <dl className="row mb-0" style={{ fontSize: '.88rem' }}>
                <dt className="col-6 text-muted fw-normal">Waiting on me</dt>
                <dd className="col-6">{open.waitingOnMe || 'Nothing'}</dd>
                <dt className="col-6 text-muted fw-normal">Last seen</dt>
                <dd className="col-6">{open.idleDays === null ? 'Never signed in' : `${open.idleDays} days ago`}</dd>
                <dt className="col-6 text-muted fw-normal">Last submission</dt>
                <dd className="col-6">{open.lastSubmission ? fmtDate(open.lastSubmission) : 'None yet'}</dd>
                <dt className="col-6 text-muted fw-normal">Next call</dt>
                <dd className="col-6">{open.nextCallDue ? fmtDateTime(open.nextCallDue) : 'Not scheduled'}</dd>
                <dt className="col-6 text-muted fw-normal">Onboarding</dt>
                <dd className="col-6">{open.onboarded ? 'Complete' : 'Still open'}</dd>
              </dl>
            </Card>
          </>
        )}
      </Drawer>
    </Page>
  )
}
