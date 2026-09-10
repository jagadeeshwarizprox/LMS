import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../../api/client'
import { TableSkeleton } from '../../components/Skeletons'
import Avatar from '../../components/Avatar'
import { Card, Empty, Grid, LoadError, Page, Stat, Tag } from '../../components/Ui'

/**
 * Every learner on one row, and the same rows summed by batch, mentor and course.
 *
 * The numbers already existed and were scattered across five screens, so nobody could
 * answer "is 56 doing worse than 55" without holding four tabs in their head. The rollups
 * are built from the same rows as the table, which is what stops the summary and the
 * detail quietly disagreeing.
 *
 * There is deliberately no single score. Rolling progress, marks, attendance and effort
 * into one number invents a weighting nobody agreed and hides which of the four is the
 * actual problem.
 *
 * A blank cell means the number does not apply yet, not zero. Somebody who has not sat a
 * test is not a zero in the average, they are simply not in it, otherwise every new
 * cohort reads as failing.
 */

const COLUMNS = [
  ['name', 'Learner'],
  ['progressPct', 'Course'],
  ['quizAvg', 'Tests'],
  ['teachAvg', 'Teach back'],
  ['tasksApproved', 'Tasks'],
  ['attendancePct', 'Attendance'],
  ['minutes28', 'Effort'],
  ['idleDays', 'Last seen']
]

function pct(v) {
  return v == null ? <span className="muted">—</span> : `${Math.round(v)}%`
}

function hours(mins) {
  if (mins == null) return <span className="muted">—</span>
  if (mins < 60) return `${mins}m`
  return `${Math.round(mins / 6) / 10}h`
}

export default function Analytics() {
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [sort, setSort] = useState('progressPct')
  const [q, setQ] = useState('')
  const [only, setOnly] = useState('ALL')

  const load = () => {
    setError(null)
    api.get('/analytics').then(setData).catch((e) => setError(e.message))
  }
  useEffect(load, [])

  const rows = useMemo(() => {
    if (!data) return []
    let r = data.learners
    if (only === 'RISK') r = r.filter((x) => x.atRisk)
    if (only === 'PREMIUM') r = r.filter((x) => x.trackType === 'PREMIUM')
    if (only === 'BATCH') r = r.filter((x) => x.trackType === 'BATCH')
    /*
     * The box searches the learner, and only the learner.
     *
     * It used to match on the batch, the mentor and the course as well, so typing a
     * learner's name could return a mentor's entire caseload because that mentor's name
     * happened to contain it. The columns are all sortable and the rollups above group by
     * batch, mentor and course already, so nothing is lost by this box meaning one thing.
     */
    if (q.trim()) {
      const needle = q.trim().toLowerCase()
      r = r.filter((x) => String(x.name || '').toLowerCase().includes(needle))
    }
    return [...r].sort((a, b) => {
      if (sort === 'name') return String(a.name).localeCompare(String(b.name))
      const av = a[sort]
      const bv = b[sort]
      /* nulls last however the column sorts: "not applicable" is not "worst" */
      if (av == null && bv == null) return 0
      if (av == null) return 1
      if (bv == null) return -1
      return sort === 'idleDays' ? bv - av : av - bv
    })
  }, [data, sort, q, only])

  if (error) return <Page title="Analytics"><LoadError error={error} onRetry={load} /></Page>
  if (!data) return <TableSkeleton />

  const t = data.totals
  const mine = data.scope === 'MINE'

  return (
    <Page
      title="Analytics"
      lede={mine
        ? 'Your learners, side by side. Sort a column to find who needs the next call.'
        : 'Every learner side by side, and the same rows summed by batch, mentor and course.'}
    >
      <Grid cols={4}>
        <Stat value={t.learners} label={mine ? 'your learners' : 'learners'} icon="people" />
        <Stat value={pct(t.progressPct)} label="average course progress" icon="roadmap" />
        <Stat
          value={t.atRisk} label="at risk" icon="risk"
          tone={t.atRisk ? 'var(--warn)' : undefined}
        />
        <Stat
          value={t.tasksOverdue} label="tasks overdue" icon="queues"
          tone={t.tasksOverdue ? 'var(--stop)' : undefined}
        />
      </Grid>

      {!mine && data.byBatch.length > 1 && (
        <Card title="By batch" note="Which cohort is drifting, before anyone reports it" className="mt-4">
          <Rollup rows={data.byBatch} />
        </Card>
      )}

      {!mine && data.byMentor.length > 1 && (
        <Card
          title="By mentor"
          note="Read this as workload and where to help, not as a league table"
          className="mt-4"
        >
          <Rollup rows={data.byMentor} />
        </Card>
      )}

      {data.byCourse.length > 1 && (
        <Card title="By course" className="mt-4">
          <Rollup rows={data.byCourse} />
        </Card>
      )}

      <Card
        title="Every learner"
        className="mt-4"
        note="A blank cell means that number does not apply yet, not zero"
        actions={
          <div className="d-flex gap-2 flex-wrap">
            <input
              className="input-quiet" value={q} placeholder="Find a learner"
              aria-label="Find a learner"
              onChange={(e) => setQ(e.target.value)}
            />
            <select value={only} onChange={(e) => setOnly(e.target.value)} aria-label="Filter">
              <option value="ALL">Everyone</option>
              <option value="RISK">At risk only</option>
              <option value="PREMIUM">Premium</option>
              <option value="BATCH">Batch</option>
            </select>
          </div>
        }
      >
        {rows.length === 0 ? (
          <Empty title="Nobody matches" icon="people">
            Clear the search or the filter to see everyone again.
          </Empty>
        ) : (
          <div className="table-responsive">
            <table className="table table-pib mb-0">
              <thead>
                <tr>
                  {COLUMNS.map(([key, label]) => (
                    <th key={key}>
                      <button
                        className={`th-sort ${sort === key ? 'is-on' : ''}`}
                        onClick={() => setSort(key)}
                      >
                        {label}
                      </button>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.learnerId} className={r.atRisk ? 'row-risk' : ''}>
                    <td>
                      <span className="who-cell">
                        <Avatar name={r.name} size={28} />
                        <span>
                          <Link to={`/mentor/learners/${r.learnerId}`}>{r.name}</Link>
                          <span className="small muted d-block">
                            {r.batch || (r.trackType === 'PREMIUM' ? 'Premium' : 'No batch')}
                            {!mine && r.mentor ? ` · ${r.mentor}` : ''}
                          </span>
                        </span>
                      </span>
                    </td>
                    <td>
                      {r.progressPct == null ? <span className="muted">—</span> : (
                        <span className="bar-cell" title={`${r.chaptersDone} of ${r.chaptersTotal} chapters`}>
                          <span className="bar-track">
                            <span className="bar-fill" style={{ width: `${r.progressPct}%` }} />
                          </span>
                          <span className="mono small">{r.progressPct}%</span>
                        </span>
                      )}
                    </td>
                    <td className="mono">
                      {r.quizAvg == null ? <span className="muted">—</span> : r.quizAvg}
                    </td>
                    <td className="mono">
                      {r.teachAvg == null ? <span className="muted">—</span> : r.teachAvg}
                    </td>
                    <td className="mono">
                      {r.tasksApproved}/{r.tasksTotal}
                      {r.tasksOverdue > 0 && <Tag kind="stop">{r.tasksOverdue} late</Tag>}
                    </td>
                    <td className="mono">{pct(r.attendancePct)}</td>
                    <td className="mono" title="Study time in the last 28 days">
                      {hours(r.minutes28)}
                    </td>
                    <td className="mono">
                      {r.idleDays == null
                        ? <Tag kind="stop">Never</Tag>
                        : r.idleDays === 0 ? 'Today' : `${r.idleDays}d`}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <p className="small muted mt-3">
        Effort is study time over the last 28 days. Attendance counts sessions the learner
        was marked at, so somebody never marked shows a blank rather than zero.
      </p>
    </Page>
  )
}

function Rollup({ rows }) {
  return (
    <div className="table-responsive">
      <table className="table table-pib mb-0">
        <thead>
          <tr>
            <th />
            <th>Learners</th><th>Progress</th><th>Tests</th>
            <th>Attendance</th><th>Effort</th><th>Overdue</th><th>At risk</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id || r.label}>
              <td><strong>{r.label}</strong></td>
              <td className="mono">{r.learners}</td>
              <td className="mono">{pct(r.progressPct)}</td>
              <td className="mono">{r.quizAvg == null ? <span className="muted">—</span> : r.quizAvg}</td>
              <td className="mono">{pct(r.attendancePct)}</td>
              <td className="mono">{hours(r.minutes28)}</td>
              <td className="mono">{r.tasksOverdue}</td>
              <td className="mono">
                {r.atRisk > 0 ? <Tag kind="wait">{r.atRisk}</Tag> : r.atRisk}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
