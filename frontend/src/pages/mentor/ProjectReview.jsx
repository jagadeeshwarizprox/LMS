import { useEffect, useState } from 'react'
import { api, downloadFile } from '../../api/client'
import { Card, Empty, LoadError, Page, Tag, fmtDate, fmtDateTime } from '../../components/Ui'
import { TableSkeleton } from '../../components/Skeletons'
import Drawer from '../../components/Drawer'
import Avatar from '../../components/Avatar'
import { useToast } from '../../context/ToastContext'

/**
 * Project review.
 *
 * A stage advances only when a mentor says so, which makes this screen the spine of the
 * whole project system rather than a queue hanging off the side of it. Oldest submission
 * first, because a learner waiting four days for a checkpoint has stopped working.
 */

const CELL = {
  APPROVED: 'ok',
  SUBMITTED: 'wait',
  CHANGES: 'stop',
  OPEN: 'open',
  LOCKED: 'locked'
}

export default function ProjectReview() {
  const [queue, setQueue] = useState(null)
  const [error, setError] = useState(null)
  const [open, setOpen] = useState(null)

  const load = () => {
    setError(null)
    api.get('/mentor/project-queue').then(setQueue).catch(setError)
  }

  useEffect(load, [])

  if (error) return <LoadError error={error} onRetry={load} />

  return (
    <Page
      title="Project reviews"
      lede="Stages waiting on you, oldest first. A stage only moves when you move it."
      actions={<button className="btn-quiet" onClick={load}>Refresh</button>}
    >
      {!queue ? <TableSkeleton /> : queue.length === 0 ? (
        <Empty title="Nothing waiting" icon="check">
          Every stage handed in has been reviewed.
        </Empty>
      ) : (
        <Card>
          <div className="table-responsive">
            <table className="table table-pib mb-0">
              <thead>
                <tr>
                  <th>Learner</th><th>Project</th><th>Stage</th>
                  <th>Waiting</th><th>Version</th><th />
                </tr>
              </thead>
              <tbody>
                {queue.map((q) => (
                  <tr key={`${q.runId}-${q.stageKey}`} className="row-clickable" onClick={() => setOpen(q)}>
                    <td>
                      <span className="who-cell">
                        <Avatar name={q.learner} size={30} />
                        <span>{q.learner}</span>
                      </span>
                    </td>
                    <td>{q.project}</td>
                    <td>{q.stageName}</td>
                    <td>
                      <Tag kind={q.waitingDays >= 3 ? 'stop' : q.waitingDays >= 1 ? 'wait' : undefined}>
                        {q.waitingDays === 0 ? 'today' : `${q.waitingDays}d`}
                      </Tag>
                      {q.overdue && <Tag kind="stop">late</Tag>}
                    </td>
                    <td className="mono">v{q.version}</td>
                    <td className="text-end">
                      <button className="btn-quiet sm" onClick={() => setOpen(q)}>Review</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {open && (
        <ReviewDrawer
          runId={open.runId}
          stageKey={open.stageKey}
          onClose={() => setOpen(null)}
          onDone={() => { setOpen(null); load() }}
        />
      )}
    </Page>
  )
}

function ReviewDrawer({ runId, stageKey, onClose, onDone }) {
  const toast = useToast()
  const [run, setRun] = useState(null)
  const [outcome, setOutcome] = useState('APPROVED')
  const [feedback, setFeedback] = useState('')
  const [scores, setScores] = useState({})
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api.get(`/mentor/project-runs/${runId}`).then(setRun).catch(() => setRun(null))
  }, [runId])

  const stage = run?.stages?.find((s) => s.key === stageKey)
  const rubric = stage?.rubric
  const last = stage?.attempts?.[stage.attempts.length - 1]

  /* the weighted average, computed the way the rubric says it should be */
  const overall = (() => {
    if (!rubric?.criteria?.length) return null
    let sum = 0, weight = 0
    for (const c of rubric.criteria) {
      const v = scores[c.key]
      if (v == null || v === '') continue
      sum += Number(v) * (c.weight || 1)
      weight += (c.weight || 1)
    }
    return weight === 0 ? null : Math.round(sum / weight)
  })()

  const send = async () => {
    setBusy(true)
    try {
      await api.post(`/mentor/project-runs/${runId}/stages/${stageKey}/review`, {
        outcome,
        score: overall,
        feedback,
        rubricScores: Object.fromEntries(
          Object.entries(scores).filter(([, v]) => v !== '' && v != null).map(([k, v]) => [k, Number(v)])
        )
      })
      toast.push(outcome === 'APPROVED' ? 'Approved. The next stage is open.' : 'Sent back for a redo.')
      onDone()
    } catch (e) {
      toast.push(e.message, 'bad')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Drawer
      open
      title={stage?.name || 'Review'}
      subtitle={run ? `${run.learner} · ${run.title}` : ''}
      onClose={onClose}
      footer={
        <>
          <button className="btn-quiet" onClick={onClose}>Close</button>
          <button
            className="btn"
            disabled={busy || (outcome === 'CHANGES' && !feedback.trim())}
            onClick={send}
            title={outcome === 'CHANGES' && !feedback.trim()
              ? 'Say what needs changing. They cannot act on a rejection with no note.'
              : ''}
          >
            {outcome === 'APPROVED' ? 'Approve and open the next stage' : 'Send back'}
          </button>
        </>
      }
    >
      {!run ? <TableSkeleton /> : (
        <>
          <p className="proj-asks">{stage?.asks}</p>

          {last && (
            <div className="proj-attempt">
              <span className="ver on">v{last.version}</span>
              <span className="mono small muted">{fmtDateTime(last.at)}</span>
              {last.notes && <p className="proj-attempt-note">{last.notes}</p>}
              {last.repoUrl && (
                <a href={last.repoUrl} target="_blank" rel="noreferrer" className="small">{last.repoUrl}</a>
              )}
              {last.demoUrl && (
                <a href={last.demoUrl} target="_blank" rel="noreferrer" className="small">{last.demoUrl}</a>
              )}
              <div className="proj-res-row">
                {last.fileIds?.map((f) => (
                  <button className="btn-quiet sm" key={f} onClick={() => downloadFile(f, 'submission')}>
                    Open file
                  </button>
                ))}
              </div>
            </div>
          )}

          {stage?.attempts?.length > 1 && (
            <details className="proj-earlier">
              <summary>Earlier versions ({stage.attempts.length - 1})</summary>
              {stage.attempts.slice(0, -1).reverse().map((a) => (
                <div className="proj-attempt" key={a.version}>
                  <span className="ver">v{a.version}</span>
                  <span className="mono small muted">{fmtDate(a.at)}</span>
                  {a.notes && <p className="proj-attempt-note">{a.notes}</p>}
                  {a.feedback && <p className="small muted">You said: {a.feedback}</p>}
                </div>
              ))}
            </details>
          )}

          {rubric && (
            <div className="proj-score">
              <span className="brief-label">{rubric.name}</span>
              {rubric.criteria.map((c) => (
                <label className="proj-score-row" key={c.key}>
                  <span>
                    <strong>{c.label}</strong>
                    {c.guidance && <span className="sub">{c.guidance}</span>}
                  </span>
                  <input
                    type="number" min="0" max="100" placeholder="—"
                    value={scores[c.key] ?? ''}
                    onChange={(e) => setScores((s) => ({ ...s, [c.key]: e.target.value }))}
                  />
                  <span className="mono small muted">×{c.weight || 1}</span>
                </label>
              ))}
              {overall != null && (
                <div className="proj-overall">
                  <span>Weighted score</span>
                  <strong className="mono">{overall}</strong>
                </div>
              )}
            </div>
          )}

          <div className="proj-outcome">
            <label className="check">
              <input
                type="radio" checked={outcome === 'APPROVED'}
                onChange={() => setOutcome('APPROVED')}
              />
              <span>Approve, and open the next stage</span>
            </label>
            <label className="check">
              <input
                type="radio" checked={outcome === 'CHANGES'}
                onChange={() => setOutcome('CHANGES')}
              />
              <span>Send back for a redo</span>
            </label>
          </div>

          <label>
            <span>{outcome === 'CHANGES' ? 'What to change' : 'Anything worth saying'}</span>
            <textarea
              rows={4} value={feedback}
              placeholder={outcome === 'CHANGES'
                ? 'This is pinned above their upload box until they send the next version.'
                : 'One thing they did well, one thing to carry into the next stage.'}
              onChange={(e) => setFeedback(e.target.value)}
            />
          </label>
        </>
      )}
    </Drawer>
  )
}

/**
 * Every learner in a batch against every stage.
 *
 * Who is stuck at scoping in week three should be a question answered by looking, not by
 * opening twenty records one at a time.
 */
export function ProjectGrid({ batchId, projectId }) {
  const [data, setData] = useState(null)

  useEffect(() => {
    if (!batchId || !projectId) return
    api.get(`/mentor/batches/${batchId}/projects/${projectId}/grid`)
      .then(setData).catch(() => setData(null))
  }, [batchId, projectId])

  if (!data) return null

  return (
    <Card title={data.project.title} note={`${data.approved} finished, ${data.waiting} waiting on a review`}>
      <div className="grid-scroll">
        <table className="proj-grid">
          <thead>
            <tr>
              <th>Learner</th>
              {data.stages.map((s) => <th key={s.key}>{s.name}</th>)}
            </tr>
          </thead>
          <tbody>
            {data.rows.map((r) => (
              <tr key={r.runId}>
                <td className="proj-grid-name">{r.learner}</td>
                {r.stages.map((c) => (
                  <td key={c.key}>
                    <span
                      className={`proj-cell c-${CELL[c.status]} ${c.overdue ? 'is-late' : ''}`}
                      title={`${c.status}${c.score != null ? ` · ${Math.round(c.score)}` : ''}`}
                    />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="proj-legend">
        <span><i className="proj-cell c-ok" /> approved</span>
        <span><i className="proj-cell c-wait" /> waiting on you</span>
        <span><i className="proj-cell c-stop" /> sent back</span>
        <span><i className="proj-cell c-open" /> their turn</span>
        <span><i className="proj-cell c-locked" /> not open yet</span>
      </div>
    </Card>
  )
}
