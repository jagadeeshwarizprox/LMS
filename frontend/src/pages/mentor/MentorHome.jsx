import { Fragment, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { useDialog } from '../../components/Dialog'
import Avatar from '../../components/Avatar'
import { RoadmapSkeleton } from '../../components/Skeletons'
import Icon from '../../components/Icon'
import { Bars, Trend } from '../../components/Charts'
import { Card, Empty, Page, Stat, Tag, fmtDate, fmtDateTime } from '../../components/Ui'

export default function MentorHome() {
  const navigate = useNavigate()
  const toast = useToast()
  const { ask } = useDialog()
  const [data, setData] = useState(null)
  const [open, setOpen] = useState(null)
  const [form, setForm] = useState({ status: 'APPROVED', score: '', feedback: '' })

  const [day, setDay] = useState(null)
  const [doubts, setDoubts] = useState([])

  const load = async () => {
    setData(await api.get('/mentor/dashboard'))
    setDay(await api.get('/mentor/day').catch(() => null))
    setDoubts(await api.get('/mentor/doubts').catch(() => []))
  }
  useEffect(() => { load() }, [])

  if (!data?.counts) return <RoadmapSkeleton />

  const review = async (kind, id) => {
    try {
      const path = kind === 'task' ? `/mentor/assignments/${id}/review` : `/mentor/projects/${id}/review`
      await api.post(path, {
        status: form.status,
        score: form.score === '' ? null : Number(form.score),
        feedback: form.feedback
      })
      toast.push('Review sent to the learner.')
      setOpen(null)
      setForm({ status: 'APPROVED', score: '', feedback: '' })
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const schedule = async (id) => {
    const r = await ask({
      title: 'Schedule this mock interview',
      body: 'The learner is told as soon as you confirm.',
      fields: [{
        name: 'when', label: 'Date and time', type: 'datetime-local', required: true
      }],
      confirmLabel: 'Schedule'
    })
    if (!r) return
    const when = r.when
    try {
      await api.post(`/mentor/mocks/${id}/schedule`, { scheduledFor: new Date(when).toISOString() })
      toast.push('Mock scheduled.')
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const record = async (id, learner) => {
    const r = await ask({
      title: `How did ${learner}'s mock go?`,
      body: 'The score and the note go straight to the learner. This closes the request.',
      fields: [
        { name: 'score', label: 'Score out of 100', type: 'number', required: true },
        { name: 'feedback', label: 'What they should work on', multiline: true, required: true }
      ],
      confirmLabel: 'Record and send'
    })
    if (!r) return
    try {
      await api.post(`/mentor/mocks/${id}/record`,
        { score: Number(r.score), feedback: r.feedback })
      toast.push('Outcome sent to the learner.')
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const decline = async (id, learner) => {
    const r = await ask({
      title: `Decline ${learner}'s request`,
      body: 'Say what they should finish first. They are told.',
      fields: [{ name: 'reason', label: 'Reason', multiline: true, required: true }],
      confirmLabel: 'Decline'
    })
    if (!r) return
    try {
      await api.post(`/mentor/mocks/${id}/decline`, { reason: r.reason })
      toast.push('Request declined.')
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const ReviewBox = ({ kind, id }) => (
    <div className="mt-2 p-3" style={{ background: 'var(--blue-050)', borderRadius: 'var(--radius)' }}>
      <div className="row g-2 align-items-end">
        <div className="col-md-3">
          <label className="form-label">Decision</label>
          <select className="form-select" value={form.status}
            onChange={(e) => setForm((f) => ({ ...f, status: e.target.value }))}>
            <option value="APPROVED">Approve</option>
            <option value="CHANGES">Ask for changes</option>
          </select>
        </div>
        {kind === 'task' && (
          <div className="col-md-2">
            <label className="form-label">Score</label>
            <input className="form-control" type="number" min="0" max="100" value={form.score}
              onChange={(e) => setForm((f) => ({ ...f, score: e.target.value }))} />
          </div>
        )}
        <div className="col-md-5">
          <label className="form-label">Feedback</label>
          <input className="form-control" value={form.feedback}
            onChange={(e) => setForm((f) => ({ ...f, feedback: e.target.value }))} />
        </div>
        <div className="col-md-2 d-flex gap-2">
          <button className="btn btn-pib w-100" onClick={() => review(kind, id)}>Send</button>
        </div>
      </div>
    </div>
  )

  const KIND = {
    ONBOARDING: 'Onboarding call', INDUCTION: 'Induction',
    DOUBT: 'Doubt clearing', GROUP_DOUBT: 'Group doubt clearing',
    LIVE: 'Live session', PROJECT: 'Project session', MOCK: 'Mock interview'
  }

  const counts = data.counts || {}

  return (
    <Page title="My desk" lede="What is waiting on you today, in the order it will bite.">
      {/*
        * Two doors, because a mentor thinks in two shapes. Their premium learners are
        * individuals they own end to end; their batches are cohorts. Everything else on
        * this page is a queue, and a queue is a bad way to answer "how is B56 doing".
        */}
      <div className="mentor-doors">
        <Link className="door door-premium" to="/mentor/learners?track=PREMIUM">
          <span className="door-eyebrow">Individual</span>
          <span className="door-count mono">{counts.premium ?? counts.premiumLearners ?? '—'}</span>
          <span className="door-title">My premium learners</span>
          <span className="door-note">Yours end to end: onboarding, one to one, biweekly calls, mocks.</span>
          <span className="door-go">Open <Icon name="chevron" /></span>
        </Link>

        <Link className="door door-batch" to="/mentor/batches">
          <span className="door-eyebrow">Cohorts</span>
          <span className="door-count mono">{counts.batches ?? '—'}</span>
          <span className="door-title">My batches</span>
          <span className="door-note">Every cohort you teach. Open one for the whole roster and its progress.</span>
          <span className="door-go">Open <Icon name="chevron" /></span>
        </Link>
      </div>

      {day && (
        <div className="desk">
          <div className="desk-main">
            <div className="eyebrow mb-2">Today</div>
            {day.sessionsToday.length === 0 && day.callsDue.length === 0 ? (
              <div className="desk-clear">Nothing scheduled today. A good day to clear the queue.</div>
            ) : (
              <div className="desk-list">
                {day.sessionsToday.map((s) => (
                  <div className="desk-item" key={s.id}>
                    <span className="desk-kind">{KIND[s.kind] || s.kind}</span>
                    <span className="desk-title mono">
                      {new Date(s.startsAt).toLocaleTimeString('en-IN',
                        { hour: '2-digit', minute: '2-digit' })}
                    </span>
                    <span className="desk-detail">{s.booked} of {s.capacity} booked</span>
                    {s.open && <span className="desk-now">open now</span>}
                  </div>
                ))}
                {day.callsDue.map((c) => (
                  <button className="desk-item as-btn" key={c.learnerId}
                    onClick={() => navigate(`/mentor/learners/${c.learnerId}`)}>
                    <span className="desk-kind">Progress call</span>
                    <span className="desk-title">{c.name}</span>
                    <span className="desk-detail">{fmtDate(c.scheduledFor)}</span>
                    {c.overdue && <span className="desk-late">overdue</span>}
                  </button>
                ))}
              </div>
            )}
          </div>

          <div className="desk-side">
            <div className="eyebrow mb-2">Waiting longest</div>
            {day.oldestWaiting.length === 0 ? (
              <div className="small muted">Queue is clear.</div>
            ) : day.oldestWaiting.map((t) => (
              <button className="age-item" key={t.id}
                onClick={() => navigate(`/mentor/learners/${t.learnerId}`)}>
                <span className={`age-days ${t.stale ? 'stale' : ''}`}>{t.waitingDays}d</span>
                <span className="age-body">
                  <strong>{t.learner}</strong>
                  <span>{t.title}</span>
                </span>
              </button>
            ))}
            <div className="desk-week">
              <Trend value={day.reviewedThisWeek} previous={day.reviewedThisWeek}
                label="reviewed this week" />
              <Trend value={day.callsHeldThisWeek} previous={day.callsHeldThisWeek}
                label="calls held" />
            </div>
          </div>
        </div>
      )}

      {/*
        * Every one of these numbers is a list somebody wants to open. They were plain
        * text, so a mentor read "6 at risk" and then had to go and find the six. Each
        * tile is a link to the list it counts.
        */}
      <div className="row g-3 mb-4">
        <div className="col-6 col-lg-3">
          <Link className="stat-link" to="/mentor/learners">
            <Stat value={data.counts.total} label="Learners assigned" />
          </Link>
        </div>
        <div className="col-6 col-lg-3">
          <Link className="stat-link" to="/mentor/tasks">
            <Stat value={data.taskQueue.length} label="Tasks to review" />
          </Link>
        </div>
        <div className="col-6 col-lg-3">
          <Link className="stat-link" to="/mentor/projects">
            <Stat value={data.projectQueue.length} label="Projects to review" />
          </Link>
        </div>
        <div className="col-6 col-lg-3">
          <Link className="stat-link" to="/mentor/learners?filter=risk">
            <Stat value={data.atRisk.length} label="At risk" tone={data.atRisk.length ? 'var(--stop)' : undefined} />
          </Link>
        </div>
      </div>

      <Card title="Tasks waiting" note="Approving the last task in a module opens the premium learner's mock automatically.">
        {data.taskQueue.length === 0 ? <Empty title="Queue is clear" /> : (
          <table className="table table-pib mb-0">
            <thead><tr><th>Learner</th><th>Task</th><th>Submitted</th><th>Link</th><th /></tr></thead>
            <tbody>
              {data.taskQueue.map((t) => (
                <Fragment key={t.id}>
                  <tr>
                    <td>
                      <span className="who-cell">
                        <Avatar name={t.learner} size={30} />
                        <Link to={`/mentor/learners/${t.learnerId}`}>{t.learner}</Link>
                      </span>
                    </td>
                    <td>{t.title}</td>
                    <td className="mono">{fmtDate(t.submittedAt)}</td>
                    <td>{t.submissionUrl ? <a href={t.submissionUrl} target="_blank" rel="noreferrer">Open</a> : '\u2014'}</td>
                    <td className="text-end">
                      <button className="btn btn-quiet" onClick={() => setOpen(open === t.id ? null : t.id)}>Review</button>
                    </td>
                  </tr>
                  {open === t.id && (
                    <tr><td colSpan={5}><ReviewBox kind="task" id={t.id} /></td></tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Card title="Projects waiting" note="One approved project is what unlocks the mock on the batch track.">
        {data.projectQueue.length === 0 ? <Empty title="Nothing pending" /> : (
          <table className="table table-pib mb-0">
            <thead><tr><th>Learner</th><th>Project</th><th>Submitted</th><th>Repo</th><th /></tr></thead>
            <tbody>
              {data.projectQueue.map((p) => (
                <Fragment key={p.id}>
                  <tr>
                    <td>
                      <span className="who-cell">
                        <Avatar name={p.learner} size={30} />
                        <Link to={`/mentor/learners/${p.learnerId}`}>{p.learner}</Link>
                      </span>
                    </td>
                    <td>{p.title}</td>
                    <td className="mono">{fmtDate(p.submittedAt)}</td>
                    <td>{p.repoUrl ? <a href={p.repoUrl} target="_blank" rel="noreferrer">Open</a> : '\u2014'}</td>
                    <td className="text-end">
                      <button className="btn btn-quiet" onClick={() => setOpen(open === p.id ? null : p.id)}>Review</button>
                    </td>
                  </tr>
                  {open === p.id && (
                    <tr><td colSpan={5}><ReviewBox kind="project" id={p.id} /></td></tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      {doubts.length > 0 && (
        <Card
          title="Where the cohort is stuck"
          note="Taken from what your learners asked the assistant in the last two weeks. Worth a look before a session."
        >
          {doubts.slice(0, 4).map((d) => (
            <div className="doubt-group" key={d.chapterId}>
              <div className="doubt-group-head">
                <span className="eyebrow">{d.module}</span>
                <strong>{d.chapter}</strong>
                <span className="doubt-count">
                  <Icon name="mock" size={14} /> {d.count} from {d.learners} learner{d.learners > 1 ? 's' : ''}
                </span>
              </div>
              <ul className="doubt-q">
                {d.questions.slice(0, 3).map((q, i) => (
                  <li key={i}><span className="doubt-who">{q.learner}</span>{q.question}</li>
                ))}
              </ul>
            </div>
          ))}
        </Card>
      )}

      <Card title="Mock interviews" note="A scheduled mock stays here until you record how it went.">
        {data.mockQueue.length === 0 ? <Empty title="No requests" /> : (
          <table className="table table-pib mb-0">
            <thead>
              <tr><th>Learner</th><th>Origin</th><th>Stage</th><th>Raised</th><th /></tr>
            </thead>
            <tbody>
              {data.mockQueue.map((m) => (
                <tr key={m.id}>
                  <td>
                      <span className="who-cell">
                        <Avatar name={m.learner} size={30} />
                        <Link to={`/mentor/learners/${m.learnerId}`}>{m.learner}</Link>
                      </span>
                    </td>
                  <td>{m.origin === 'AUTO' ? 'Module complete' : 'Requested'}</td>
                  <td>
                    {m.status === 'SCHEDULED'
                      ? <Tag kind="ok">Held {fmtDateTime(m.scheduledFor)}</Tag>
                      : <Tag kind="wait">Waiting on you</Tag>}
                  </td>
                  <td className="mono">{fmtDate(m.createdAt)}</td>
                  <td className="text-end">
                    {m.status === 'SCHEDULED' ? (
                      <>
                        <button className="btn btn-quiet me-2"
                          onClick={() => schedule(m.id)}>Move</button>
                        <button className="btn btn-pib"
                          onClick={() => record(m.id, m.learner)}>Record outcome</button>
                      </>
                    ) : (
                      <>
                        <button className="btn btn-quiet me-2"
                          onClick={() => decline(m.id, m.learner)}>Decline</button>
                        <button className="btn btn-pib"
                          onClick={() => schedule(m.id)}>Schedule</button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </Page>
  )
}
