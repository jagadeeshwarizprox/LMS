import { useEffect, useRef, useState } from 'react'
import { api, downloadFile } from '../api/client'
import { useToast } from '../context/ToastContext'
import Avatar from './Avatar'
import FileUpload from './FileUpload'
import Icon from './Icon'
import { Empty, Tag, fmtDateTime } from './Ui'

/**
 * The conversation around a task, which is most of the teaching.
 *
 * Every submission and every review is a turn. A resubmission used to overwrite the
 * last one, so what was asked and what came back simply vanished. Nothing here is ever
 * replaced.
 */
export default function TaskThread({ assignmentId, role = 'MENTOR', onChanged }) {
  const toast = useToast()
  const [data, setData] = useState(null)
  const [comment, setComment] = useState('')
  const [status, setStatus] = useState('APPROVED')
  const [feedback, setFeedback] = useState('')
  const [scores, setScores] = useState({})
  const [busy, setBusy] = useState(false)
  const [url, setUrl] = useState('')
  const [files, setFiles] = useState([])
  const [notes, setNotes] = useState('')
  const foot = useRef(null)

  const load = () => api.get(`/${role === 'MENTOR' ? 'mentor' : 'learner'}/tasks/${assignmentId}`)
    .then(setData).catch((e) => toast.push(e.message, 'bad'))

  useEffect(() => { load() }, [assignmentId])
  useEffect(() => { foot.current?.scrollIntoView({ block: 'nearest' }) }, [data])

  if (!data) return null
  const { assignment: a, events, rubric } = data
  const byId = Object.fromEntries((a.files || []).map((f) => [f.id, f]))
  const nameOf = (id) => byId[id]?.filename

  const send = async () => {
    if (!comment.trim()) return
    const base = role === 'MENTOR' ? 'mentor' : 'learner'
    await api.post(`/${base}/tasks/${assignmentId}/comment`, { body: comment })
    setComment('')
    await load()
  }

  /**
   * A resubmission is a new turn, never a replacement, so what was asked and what
   * came back both stay readable. Until this existed a learner asked for changes
   * had nowhere in the product to put them.
   */
  const submit = async () => {
    setBusy(true)
    try {
      await api.post(`/learner/tasks/${assignmentId}/submit`,
        { submissionUrl: url || null, fileIds: files.map((f) => f.id), notes: notes || null })
      setUrl(''); setFiles([]); setNotes('')
      toast.push('Sent to your mentor.')
      await load()
      onChanged?.()
    } catch (e) {
      toast.push(e.message, 'bad')
    } finally {
      setBusy(false)
    }
  }

  /**
   * The review lands on screen straight away and the request goes ten seconds later.
   * A mentor working through a queue is right almost every time, so asking them to
   * confirm each one costs more than the rare mistake does. Undo covers the mistake.
   */
  const review = async () => {
    const snapshot = data
    const optimistic = {
      ...data,
      assignment: { ...data.assignment, status, mentorFeedback: feedback },
      events: [...data.events, {
        kind: 'REVIEWED', actor: 'You', role: 'MENTOR', status,
        body: feedback, at: new Date().toISOString(), pending: true
      }]
    }
    setData(optimistic)
    setFeedback('')

    toast.pushUndo({
      message: status === 'APPROVED' ? 'Approved. The learner will be told.' : 'Changes asked.',
      commit: async () => {
        await api.post(`/mentor/tasks/${assignmentId}/review`, {
          status, feedback, rubricScores: Object.keys(scores).length ? scores : null
        })
        await load()
        onChanged?.()
      },
      revert: () => setData(snapshot)
    })
  }

  const weighted = rubric && Object.keys(scores).length
    ? Math.round(rubric.criteria.reduce((acc, c) => acc + (scores[c.key] || 0) * c.weight, 0)
        / rubric.criteria.reduce((acc, c) => acc + c.weight, 0) * 10) / 10
    : null

  return (
    <div className="thread">
      <div className="thread-head">
        <div>
          <strong>{a.title}</strong>
          {a.brief && <div className="small muted mt-1">{a.brief}</div>}
        </div>
        <div className="thread-meta">
          {a.dueAt && (
            <Tag kind={new Date(a.dueAt) < new Date() && a.status !== 'APPROVED' ? 'stop' : 'batch'}>
              due {new Date(a.dueAt).toLocaleDateString('en-IN', { day: '2-digit', month: 'short' })}
            </Tag>
          )}
          <Tag kind={a.status === 'APPROVED' ? 'ok' : a.status === 'CHANGES' ? 'stop' : 'wait'}>
            {a.status === 'ASSIGNED' ? 'Not submitted' : a.status.toLowerCase()}
          </Tag>
          {a.submissionCount > 1 && (
            <span className="small muted">{a.submissionCount} submissions</span>
          )}
        </div>
      </div>

      {(a.submissionCount || 0) > 1 && (
        <div className="ver-strip" aria-label="Submission history">
          {Array.from({ length: a.submissionCount }, (_, n) => n + 1).map((v) => (
            <span className={`ver ${v === a.submissionCount ? 'on' : ''}`} key={v}>v{v}</span>
          ))}
          <span className="ver-note">Every version is kept. A resubmission never overwrites one.</span>
        </div>
      )}

      <div className="thread-body">
        {events.length === 0 ? <Empty title="Nothing yet" /> : events.map((e, i) => (
          <div className={`turn turn-${e.role?.toLowerCase() || 'system'}`} key={i}>
            <Avatar name={e.actor || 'System'} size={30} />
            <div className="turn-body">
              <div className="turn-head">
                <strong>{e.actor}</strong>
                <span className="turn-kind">{
                  { ASSIGNED: 'set this task', SUBMITTED: 'submitted', REVIEWED: 'reviewed',
                    COMMENT: 'commented', DUE_CHANGED: 'changed the due date' }[e.kind] || e.kind
                }</span>
                <span className="turn-at mono">{fmtDateTime(e.at)}</span>
              </div>
              {e.status && (
                <Tag kind={e.status === 'APPROVED' ? 'ok' : 'stop'}>
                  {e.status === 'APPROVED' ? 'Approved' : 'Changes asked'}
                  {e.score != null ? ` \u00b7 ${e.score}` : ''}
                </Tag>
              )}
              {e.body && <div className="turn-text">{e.body}</div>}
            {e.pending && <span className="turn-pending">Sending, undo while you can</span>}
              {e.submissionUrl && (
                <a className="turn-link" href={e.submissionUrl} target="_blank" rel="noreferrer">
                  <Icon name="resources" size={14} /> Open submission
                </a>
              )}
              {/* the filename, not "Download file 2": a mentor with three attachments
                  had no way to tell the notebook from the dataset before opening both */}
              {(e.fileIds?.length ? e.fileIds : (e.fileId ? [e.fileId] : [])).map((fid, n) => (
                <button
                  key={fid}
                  className="turn-link as-btn"
                  onClick={() => downloadFile(fid).catch((err) => toast.push(err.message, 'bad'))}
                >
                  <Icon name="import" size={14} /> {nameOf(fid) || `File ${n + 1}`}
                </button>
              ))}
            </div>
          </div>
        ))}
        <div ref={foot} />
      </div>

      {/*
        * What the task was set from, beside what came back.
        *
        * The thread carried the brief text and the learner's upload and not the dataset
        * or rubric document the work was written against, so a mentor was marking a
        * notebook without the question in front of them.
        */}
      {role === 'MENTOR' && a.briefFiles?.length > 0 && (
        <div className="brief-files mb-3">
          <div className="eyebrow mb-2">
            What they were given{a.chapterTitle ? ` \u00b7 ${a.chapterTitle}` : ''}
          </div>
          <div className="brief-file-row">
            {a.briefFiles.map((f) => (
              <button
                key={f.id}
                className="brief-file"
                disabled={f.missing}
                onClick={() => downloadFile(f.id).catch((err) => toast.push(err.message, 'bad'))}
              >
                <Icon name="import" size={14} />
                <span className="brief-file-name">{f.filename}</span>
              </button>
            ))}
          </div>
        </div>
      )}

      {role === 'MENTOR' && a.status !== 'ASSIGNED' && (
        <div className="thread-review">
          {rubric && (
            <div className="rubric">
              <div className="eyebrow mb-2">{rubric.name}</div>
              {rubric.criteria.map((c) => (
                <div className="rubric-row" key={c.key}>
                  <div>
                    <strong>{c.label}</strong>
                    <div className="small muted">{c.guidance}</div>
                  </div>
                  <div className="rubric-scale">
                    {[1, 2, 3, 4, 5].map((n) => (
                      <button
                        key={n}
                        className={`scale ${scores[c.key] === n * 20 ? 'on' : ''}`}
                        onClick={() => setScores((s) => ({ ...s, [c.key]: n * 20 }))}
                      >
                        {n}
                      </button>
                    ))}
                  </div>
                </div>
              ))}
              {/* out of what, which the mentor previously had to guess at */}
              {weighted !== null && (
                <div className="rubric-total">
                  Weighted score <strong>{weighted}</strong>
                  {a.maxMarks ? <span className="muted"> of {a.maxMarks}</span> : null}
                </div>
              )}
            </div>
          )}

          <div className="row g-2 align-items-end">
            <div className="col-md-3">
              <label className="form-label">Decision</label>
              <select className="form-select" value={status} onChange={(e) => setStatus(e.target.value)}>
                <option value="APPROVED">Approve</option>
                <option value="CHANGES">Send back for redo</option>
              </select>
            </div>
            <div className="col-md-7">
              <label className="form-label">Feedback</label>
              <input className="form-control" value={feedback}
                onChange={(e) => setFeedback(e.target.value)}
                placeholder={status === 'CHANGES'
                  ? 'What to change. This is pinned above their upload box.'
                  : 'One thing they can act on'} />
            </div>
            <div className="col-md-2">
              <button
                className="btn btn-pib w-100" onClick={review}
                disabled={busy || (status === 'CHANGES' && !feedback.trim())}
                title={status === 'CHANGES' && !feedback.trim()
                  ? 'A redo needs a reason. They cannot act on a rejection with no note.'
                  : ''}
              >
                {status === 'CHANGES' ? 'Send back' : 'Approve'}
              </button>
            </div>
          </div>
        </div>
      )}

      {role === 'LEARNER' && a.status !== 'APPROVED' && (
        <div className={`thread-review ${a.status === 'CHANGES' ? 'is-redo' : ''}`}>
          {/*
            * On a redo the feedback has to be in front of the person doing the work.
            * It used to be somewhere up the thread, which meant scrolling back through
            * every earlier turn to find out what to change. It is pinned here now, with
            * the version they are about to replace named next to it.
            */}
          {a.status === 'CHANGES' && a.mentorFeedback && (
            <div className="redo-note">
              <span className="redo-tag">Changes asked</span>
              <p className="redo-text">{a.mentorFeedback}</p>
              <span className="redo-meta">
                You are sending version {(a.submissionCount || 1) + 1}. Nothing you sent before is lost.
              </span>
            </div>
          )}
          <div className="eyebrow mb-2">
            {a.status === 'CHANGES' ? 'Redo and send it again' : 'Hand this in'}
          </div>
          <FileUpload
            purpose="TASK"
            linkedId={assignmentId}
            label="Attach your work"
            onAllUploaded={(fs) => setFiles((prev) => [...prev, ...fs])}
          />
          <div className="row g-2 align-items-end mt-2">
            <div className="col-md-5">
              <label className="form-label">Link, if the work lives somewhere else</label>
              <input className="form-control" value={url}
                onChange={(e) => setUrl(e.target.value)}
                placeholder="Repository or notebook link" />
            </div>
            <div className="col-md-5">
              <label className="form-label">Anything your mentor should know</label>
              <input className="form-control" value={notes}
                onChange={(e) => setNotes(e.target.value)}
                placeholder="What you tried, where you got stuck" />
            </div>
            <div className="col-md-2">
              <button className="btn btn-pib w-100" onClick={submit}
                disabled={busy || (!url.trim() && files.length === 0)}>
                {a.status === 'CHANGES' ? 'Resend' : 'Submit'}
              </button>
            </div>
          </div>
        </div>
      )}

      <div className="thread-reply">
        <input
          className="form-control"
          placeholder="Add a comment"
          value={comment}
          onChange={(e) => setComment(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && send()}
        />
        <button className="btn btn-quiet" onClick={send} disabled={!comment.trim()}>Comment</button>
      </div>
    </div>
  )
}
