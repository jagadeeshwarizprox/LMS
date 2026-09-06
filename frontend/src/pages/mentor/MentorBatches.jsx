import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../../api/client'
import Avatar from '../../components/Avatar'
import Drawer from '../../components/Drawer'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, Loading, Page, Stat, Tag, fmtDate } from '../../components/Ui'

/**
 * A mentor's cohorts, batch first.
 *
 * The only way to reach a batch used to be the learner list with its batch column,
 * which stops being usable somewhere around the second cohort. A batch has one mentor,
 * fixed when it was created, so this is the natural shape: the batches, then the people
 * in one of them.
 */
export default function MentorBatches() {
  const [rows, setRows] = useState(null)
  const [error, setError] = useState(null)
  const [open, setOpen] = useState(null)
  const [roster, setRoster] = useState(null)

  const load = () => api.get('/mentor/batches')
    .then((r) => { setRows(r); setError(null) })
    .catch((e) => setError(e.message))

  useEffect(() => { load() }, [])

  const openBatch = async (b) => {
    setOpen(b)
    setRoster(null)
    try {
      setRoster(await api.get(`/mentor/batches/${b.id}/roster`))
    } catch (e) {
      setRoster([])
    }
  }

  if (error) {
    return (
      <Page title="My batches">
        <Empty title="That did not load" icon="risk" action={
          <button className="btn btn-pib" onClick={load}>Try again</button>
        }>{error}</Empty>
      </Page>
    )
  }
  if (!rows) return <TableSkeleton />

  const mine = rows.filter((b) => b.mine)
  const running = rows.filter((b) => b.open)

  return (
    <Page title="My batches" lede="Every cohort you teach, and who is in it.">
      <div className="row g-3 mb-4">
        <div className="col-6 col-lg-3"><Stat value={rows.length} label="Batches" /></div>
        <div className="col-6 col-lg-3"><Stat value={mine.length} label="Yours" /></div>
        <div className="col-6 col-lg-3"><Stat value={running.length} label="Running" /></div>
        <div className="col-6 col-lg-3">
          <Stat value={rows.reduce((n, b) => n + b.size, 0)} label="Learners" />
        </div>
      </div>

      {rows.length === 0 ? (
        <Empty title="No batches yet">
          Batches appear here once an admin creates one with you as its mentor.
        </Empty>
      ) : (
        <div className="batch-grid">
          {rows.map((b) => (
            <button className={`batch-card ${b.mine ? 'is-mine' : ''}`} key={b.id} onClick={() => openBatch(b)}>
              <header>
                <span className="batch-code mono">{b.code}</span>
                {b.open ? <Tag kind="ok">Running</Tag> : <Tag kind="wait">Closed</Tag>}
              </header>
              <p className="batch-name">{b.name || b.bundle || 'Batch'}</p>
              <dl className="batch-facts">
                <div><dt>Learners</dt><dd className="mono">{b.size}</dd></div>
                <div><dt>Starts</dt><dd className="mono">{fmtDate(b.startDate)}</dd></div>
                <div><dt>Induction</dt><dd>{b.inductionDone ? 'Held' : 'Not yet'}</dd></div>
                <div><dt>On hold</dt><dd className="mono">{b.onHold || 0}</dd></div>
              </dl>
              <footer>
                {b.mine ? <span className="batch-mine">Yours</span> : <span className="batch-other">{b.mentor}</span>}
                <span className="batch-go">Open roster</span>
              </footer>
            </button>
          ))}
        </div>
      )}

      <Drawer
        open={Boolean(open)}
        title={open ? `${open.code} roster` : ''}
        subtitle={open?.name || open?.bundle}
        onClose={() => setOpen(null)}
        footer={open?.whatsappLink && (
          <a className="btn btn-quiet" href={open.whatsappLink} target="_blank" rel="noreferrer">
            Open the WhatsApp group
          </a>
        )}
      >
        {roster === null && <Loading label="Reading the roster" />}
        {roster?.length === 0 && (
          <Empty title="Nobody in this batch yet">
            Learners appear here as an admin places them.
          </Empty>
        )}
        {roster?.length > 0 && (
          <>
            <div className="roster-summary">
              <span><strong className="mono">{roster.filter((l) => l.atRisk).length}</strong> at risk</span>
              <span><strong className="mono">{roster.reduce((n, l) => n + (l.awaitingReview || 0), 0)}</strong> to review</span>
              <span><strong className="mono">{roster.reduce((n, l) => n + (l.overdue || 0), 0)}</strong> overdue</span>
              <span>
                <strong className="mono">
                  {Math.round(roster.reduce((n, l) => n + (l.percent || 0), 0) / roster.length)}%
                </strong> average
              </span>
            </div>

            <div className="table-responsive">
              <table className="table table-pib mb-0 roster-table">
                <thead>
                  <tr>
                    <th>Learner</th><th>Progress</th><th>Videos</th><th>Last seen</th>
                    <th>To review</th><th>Overdue</th><th />
                  </tr>
                </thead>
                <tbody>
                  {[...roster]
                    .sort((a, b) => (b.atRisk === a.atRisk ? (a.percent || 0) - (b.percent || 0) : b.atRisk ? 1 : -1))
                    .map((l) => (
                      <tr key={l.learnerId} className={l.atRisk ? 'is-risk' : ''}>
                        <td>
                          <span className="who-cell">
                            <Avatar name={l.name} size={30} track={l.trackType} />
                            <span>
                              {l.name}
                              {l.atRisk && <Tag kind="stop">At risk</Tag>}
                              {l.onHold && <Tag kind="wait">On hold</Tag>}
                              <div className="mono small" style={{ color: 'var(--ink-30)' }}>{l.email}</div>
                            </span>
                          </span>
                        </td>
                        <td data-label="Progress" style={{ minWidth: 120 }}>
                          <div className="roster-bar">
                            <i style={{ width: `${l.percent || 0}%` }} />
                          </div>
                          <span className="mono small">{l.percent != null ? `${l.percent}%` : '\u2014'}</span>
                        </td>
                        <td data-label="Videos" className="mono small">
                          {l.videosFinished == null ? '\u2014' : (
                            <>
                              {l.videosFinished} done
                              {l.videosStarted > 0 && <span className="learn-part"> · {l.videosStarted} part</span>}
                            </>
                          )}
                        </td>
                        <td data-label="Last seen" className="mono small">
                          {l.idleDays == null ? 'never' : l.idleDays === 0 ? 'today' : `${l.idleDays}d ago`}
                        </td>
                        <td data-label="To review" className="mono">{l.awaitingReview || 0}</td>
                        <td data-label="Overdue" className="mono">
                          {l.overdue ? <span className="roster-overdue">{l.overdue}</span> : 0}
                        </td>
                        <td className="text-end">
                          <Link className="btn btn-quiet btn-sm" to={`/mentor/learners/${l.learnerId}`}>
                            Open
                          </Link>
                        </td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </Drawer>
    </Page>
  )
}
