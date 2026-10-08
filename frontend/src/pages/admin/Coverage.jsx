import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, LoadError, Page, Stat, Tag, fmtDate } from '../../components/Ui'

const WINDOWS = [[7, 'Last week'], [28, 'Last 4 weeks'], [90, 'Last 3 months']]

const KIND = {
  DOUBT: 'Doubt clearing', RECAP: 'Recap', PROJECT: 'Project session',
  INDUSTRY: 'Industry session', INDUCTION: 'Induction', MOCK_DEBRIEF: 'Mock debrief'
}

/**
 * Recording coverage.
 *
 * Each mentor's queue tells them what they owe. This tells the office who is behind:
 * of the sessions that should have produced a recording, how many did, per mentor.
 * One to ones are left out, because they were never meant for the library.
 */
export default function Coverage() {
  const [days, setDays] = useState(28)
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)

  const load = () => {
    setError(null)
    setData(null)
    api.get(`/admin/recordings/coverage?days=${days}`).then(setData).catch((e) => setError(e.message))
  }
  useEffect(() => { load() }, [days])

  const picker = (
    <select className="form-select form-select-sm" style={{ width: 170 }} value={days}
      onChange={(e) => setDays(Number(e.target.value))} aria-label="Window">
      {WINDOWS.map(([d, l]) => <option key={d} value={d}>{l}</option>)}
    </select>
  )

  if (error) return <Page title="Recording coverage" actions={picker}><LoadError error={error} onRetry={load} /></Page>
  if (!data) return <TableSkeleton />

  return (
    <Page
      title="Recording coverage"
      lede="Of the sessions that should have a recording, how many do, and who is behind."
      actions={picker}
    >
      <div className="row g-3 mb-3">
        <div className="col-6 col-lg-3"><Stat value={data.held} label="Sessions held" icon="sessions" /></div>
        <div className="col-6 col-lg-3"><Stat value={data.recorded} label="Recorded" icon="recordings" /></div>
        <div className="col-6 col-lg-3">
          <Stat value={data.percent} label="Percent covered" icon="check"
            tone={data.percent < 80 ? 'var(--stop)' : undefined} />
        </div>
        <div className="col-6 col-lg-3">
          <Stat value={data.overdue} label="Missing over a week" icon="risk"
            tone={data.overdue ? 'var(--stop)' : undefined} />
        </div>
      </div>

      <Card title="By mentor" note="Most missing first.">
        {data.byMentor.length === 0 ? (
          <Empty title="No sessions held in this window" />
        ) : (
          <table className="table table-pib mb-0">
            <thead><tr><th>Mentor</th><th>Held</th><th>Recorded</th><th>Missing</th><th>Covered</th></tr></thead>
            <tbody>
              {data.byMentor.map((m) => (
                <tr key={m.mentorId || 'none'}>
                  <td>{m.mentor}</td>
                  <td className="mono">{m.held}</td>
                  <td className="mono">{m.recorded}</td>
                  <td>
                    {m.missing === 0 ? <span className="mono">0</span>
                      : <span><span className="mono">{m.missing}</span>
                        {m.overdue > 0 && <> <Tag kind="stop">{m.overdue} over a week</Tag></>}</span>}
                  </td>
                  <td>
                    <div className="d-flex align-items-center gap-2">
                      <div className="cov-bar"><i style={{ width: `${m.percent}%` }} /></div>
                      <span className="mono small">{m.percent}%</span>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Card title="Still missing" note="The sessions behind the numbers above, oldest first.">
        {data.missing.length === 0 ? (
          <Empty title="Nothing missing" icon="check">Every session in this window has its recording.</Empty>
        ) : (
          <table className="table table-pib mb-0">
            <thead><tr><th>Session</th><th>Held</th><th>Mentor</th><th>Waiting</th></tr></thead>
            <tbody>
              {data.missing.map((s) => (
                <tr key={s.slotId}>
                  <td>
                    <strong>{s.suggestedTitle}</strong>
                    <div className="small muted">{KIND[s.recordingKind] || s.recordingKind}{s.batch ? ` · ${s.batch}` : ''}</div>
                  </td>
                  <td className="mono">{fmtDate(s.heldOn)}</td>
                  <td className="small">{s.mentor || 'Nobody assigned'}</td>
                  <td>{s.overdue ? <Tag kind="stop">{s.daysWaiting}d</Tag> : <span className="mono">{s.daysWaiting}d</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </Page>
  )
}
