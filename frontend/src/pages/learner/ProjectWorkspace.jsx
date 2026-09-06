import { useEffect, useState } from 'react'
import { api, downloadFile } from '../../api/client'
import { Card, Empty, LoadError, Page, Tag, fmtDate } from '../../components/Ui'
import { TableSkeleton } from '../../components/Skeletons'
import FileUpload from '../../components/FileUpload'
import Icon from '../../components/Icon'
import { useToast } from '../../context/ToastContext'

/**
 * The project workspace.
 *
 * What existed before was a form: type the title of something you built elsewhere and a
 * mentor approves it. That cannot be taught against. This is a brief, a rail of stages,
 * and one stage open at a time with what it wants written on it.
 *
 * A stage never opens itself. That is the whole design: a mentor decides, so nobody
 * arrives at the demo having built nothing.
 */

const STATUS_TONE = {
  APPROVED: 'ok',
  SUBMITTED: 'wait',
  CHANGES: 'stop',
  OPEN: undefined,
  LOCKED: undefined
}

const STATUS_WORD = {
  APPROVED: 'Approved',
  SUBMITTED: 'With your mentor',
  CHANGES: 'Changes asked',
  OPEN: 'Your turn',
  LOCKED: 'Locked'
}

export default function ProjectWorkspace() {
  const [runs, setRuns] = useState(null)
  const [openId, setOpenId] = useState(null)
  const [error, setError] = useState(null)

  const load = () => {
    setError(null)
    api.get('/learner/project-runs')
      .then((r) => {
        setRuns(r)
        if (r.length && !openId) setOpenId(r[0].runId)
      })
      .catch(setError)
  }

  useEffect(load, [])

  if (error) return <LoadError error={error} onRetry={load} />
  if (!runs) return <Page title="Projects"><TableSkeleton /></Page>

  if (runs.length === 0) {
    return (
      <Page title="Projects" lede="Real work, briefed the way a client would brief it.">
        <Empty title="No project yet" icon="projects">
          Your mentor puts you on a project once you are far enough into the course.
        </Empty>
      </Page>
    )
  }

  return (
    <Page title="Projects" lede="Real work, briefed the way a client would brief it.">
      {runs.length > 1 && (
        <div className="proj-switch">
          {runs.map((r) => (
            <button
              key={r.runId}
              className={r.runId === openId ? 'on' : ''}
              onClick={() => setOpenId(r.runId)}
            >
              {r.title}
              <span className="mono">{r.stagesDone}/{r.stageCount}</span>
            </button>
          ))}
        </div>
      )}
      {openId && <RunView runId={openId} onChanged={load} />}
    </Page>
  )
}

function RunView({ runId, onChanged }) {
  const toast = useToast()
  const [run, setRun] = useState(null)
  const [active, setActive] = useState(null)

  const load = () => api.get(`/learner/project-runs/${runId}`).then((r) => {
    setRun(r)
    setActive((prev) => prev || r.currentStage)
  }).catch(() => setRun(null))

  useEffect(() => { setRun(null); setActive(null); load() }, [runId])

  if (!run) return <TableSkeleton />

  const stage = run.stages.find((s) => s.key === active) || run.stages[0]

  return (
    <>
      <Card
        title={run.title}
        note={run.subtitle}
        actions={
          run.status === 'APPROVED'
            ? <Tag kind="ok">Finished</Tag>
            : <span className="mono small muted">{run.stagesDone} of {run.stageCount} stages done</span>
        }
      >
        {run.clientContext && (
          <div className="proj-client">
            <span className="brief-label">Who this is for</span>
            <p>{run.clientContext}</p>
          </div>
        )}
        {run.brief && <div className="proj-brief">{run.brief}</div>}
        {run.successCriteria && (
          <div className="proj-success">
            <span className="brief-label">What finished looks like</span>
            <p>{run.successCriteria}</p>
          </div>
        )}

        {(run.resourceFileIds?.length > 0 || run.resourceLinks?.length > 0) && (
          <div className="proj-res">
            <span className="brief-label">What you have been given</span>
            <div className="proj-res-row">
              {run.resourceFileIds.map((f) => (
                <button className="btn-quiet sm" key={f} onClick={() => downloadFile(f, 'project-file')}>
                  <Icon name="import" size={14} /> Download
                </button>
              ))}
              {run.resourceLinks.map((l, i) => (
                <a className="btn-quiet sm" key={i} href={l.url} target="_blank" rel="noreferrer">
                  {l.label || 'Open'}
                </a>
              ))}
            </div>
          </div>
        )}
      </Card>

      <div className="proj-layout">
        <nav className="proj-rail" aria-label="Project stages">
          {run.stages.map((s, i) => (
            <button
              key={s.key}
              className={`proj-step st-${s.status.toLowerCase()} ${s.key === stage.key ? 'on' : ''}`}
              onClick={() => setActive(s.key)}
              disabled={s.status === 'LOCKED'}
            >
              <span className="proj-step-mark">{s.status === 'APPROVED' ? '✓' : i + 1}</span>
              <span className="proj-step-body">
                <span className="proj-step-name">{s.name}</span>
                <span className="proj-step-status">{STATUS_WORD[s.status]}</span>
              </span>
              {s.overdue && s.status !== 'APPROVED' && <Tag kind="stop">Late</Tag>}
            </button>
          ))}
        </nav>

        <div className="proj-stage">
          <StagePanel
            run={run}
            stage={stage}
            onDone={() => { load(); onChanged?.() }}
            toast={toast}
          />
        </div>
      </div>
    </>
  )
}

function StagePanel({ run, stage, onDone, toast }) {
  const [notes, setNotes] = useState('')
  const [repoUrl, setRepoUrl] = useState('')
  const [demoUrl, setDemoUrl] = useState('')
  const [fileIds, setFileIds] = useState([])
  const [busy, setBusy] = useState(false)

  useEffect(() => { setNotes(''); setRepoUrl(''); setDemoUrl(''); setFileIds([]) }, [stage.key])

  const canSubmit = stage.submittable
    && (stage.status === 'OPEN' || stage.status === 'CHANGES')
    && (notes.trim() || repoUrl.trim() || fileIds.length > 0)

  const send = async () => {
    setBusy(true)
    try {
      const r = await api.post(`/learner/project-runs/${run.runId}/stages/${stage.key}`, {
        notes, repoUrl, demoUrl, fileIds
      })
      toast.push(`Version ${r.version} sent to your mentor.`)
      onDone()
    } catch (e) {
      toast.push(e.message, 'bad')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card
      title={stage.name}
      note={stage.dueAt ? `Due ${fmtDate(stage.dueAt)}` : null}
      actions={<Tag kind={STATUS_TONE[stage.status]}>{STATUS_WORD[stage.status]}</Tag>}
    >
      <p className="proj-asks">{stage.asks}</p>
      {stage.guidance && <p className="proj-guidance">{stage.guidance}</p>}

      {stage.status === 'CHANGES' && stage.feedback && (
        <div className="redo-note">
          <span className="redo-tag">Changes asked</span>
          <p className="redo-text">{stage.feedback}</p>
          <span className="redo-meta">
            You are sending version {(stage.attempts?.length || 0) + 1}. Nothing you sent before is lost.
          </span>
        </div>
      )}

      {stage.status === 'APPROVED' && (
        <div className="proj-approved">
          <Icon name="check" size={15} />
          <span>
            Approved{stage.score != null ? ` at ${Math.round(stage.score)}` : ''}
            {stage.reviewedAt ? ` on ${fmtDate(stage.reviewedAt)}` : ''}.
          </span>
          {stage.feedback && <p className="proj-approved-note">{stage.feedback}</p>}
        </div>
      )}

      {stage.rubric && (
        <div className="proj-rubric">
          <span className="brief-label">Scored on</span>
          <ul>
            {stage.rubric.criteria.map((c) => (
              <li key={c.key}>
                <strong>{c.label}</strong>
                {c.guidance && <span className="muted"> {c.guidance}</span>}
                {stage.rubricScores?.[c.key] != null && (
                  <span className="mono proj-rubric-score">{stage.rubricScores[c.key]}</span>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}

      {stage.attempts?.length > 0 && (
        <div className="proj-history">
          <span className="brief-label">What you have sent</span>
          {stage.attempts.map((a) => (
            <div className="proj-attempt" key={a.version}>
              <span className="ver on">v{a.version}</span>
              <span className="mono small muted">{fmtDate(a.at)}</span>
              {a.notes && <p className="proj-attempt-note">{a.notes}</p>}
              {a.repoUrl && (
                <a href={a.repoUrl} target="_blank" rel="noreferrer" className="small">{a.repoUrl}</a>
              )}
              {a.fileIds?.map((f) => (
                <button className="btn-quiet sm" key={f} onClick={() => downloadFile(f, 'submission')}>
                  Open file
                </button>
              ))}
              {a.outcome && (
                <Tag kind={a.outcome === 'APPROVED' ? 'ok' : 'stop'}>
                  {a.outcome === 'APPROVED' ? 'Approved' : 'Sent back'}
                </Tag>
              )}
            </div>
          ))}
        </div>
      )}

      {stage.submittable && (stage.status === 'OPEN' || stage.status === 'CHANGES') && (
        <div className="proj-submit">
          <label>
            <span>What you did, and anything you want read first</span>
            <textarea rows={4} value={notes} onChange={(e) => setNotes(e.target.value)} />
          </label>

          <div className="form-grid">
            <label>
              <span>Repository</span>
              <input value={repoUrl} placeholder="https://" onChange={(e) => setRepoUrl(e.target.value)} />
            </label>
            {stage.key === 'DEMO' && (
              <label>
                <span>Recorded walkthrough</span>
                <input value={demoUrl} placeholder="https://" onChange={(e) => setDemoUrl(e.target.value)} />
              </label>
            )}
          </div>

          <FileUpload
            purpose="PROJECT"
            linkedId={run.runId}
            onUploaded={(f) => setFileIds((prev) => [...prev, f.id])}
          />
          {fileIds.length > 0 && (
            <p className="small muted">{fileIds.length} file{fileIds.length === 1 ? '' : 's'} attached.</p>
          )}

          <button className="btn" disabled={!canSubmit || busy} onClick={send}>
            {busy ? 'Sending' : stage.status === 'CHANGES' ? 'Send the new version' : 'Send to my mentor'}
          </button>
        </div>
      )}

      {stage.status === 'SUBMITTED' && (
        <p className="muted small">
          With your mentor. You will get an email when it has been looked at.
        </p>
      )}

      {stage.status === 'LOCKED' && (
        <p className="muted small">This opens once the stage before it is approved.</p>
      )}
    </Card>
  )
}
