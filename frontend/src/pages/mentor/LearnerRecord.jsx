import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { api, downloadFile } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { useAuth } from '../../context/AuthContext'
import TabBar from '../../components/TabBar'
import Avatar from '../../components/Avatar'
import { Bars, Ring, Trend } from '../../components/Charts'
import MovePanel from '../../components/MovePanel'
import Icon from '../../components/Icon'
import { TableSkeleton } from '../../components/Skeletons'
import { Bar, Card, Empty, LoadError, Page, StatusTag, Tag, fmtDate, fmtDateTime } from '../../components/Ui'

const TABS = [
  ['overview', 'Overview'],
  ['learning', 'Learning'],
  ['move', 'Move'],
  ['background', 'Background'],
  ['resume', 'Resume'],
  ['assignments', 'Assignments'],
  ['tests', 'Tests and understanding'],
  ['activity', 'Activity'],
  ['notes', 'Notes and goals']
]

function SectionDump({ title, data }) {
  if (!data || Object.keys(data).length === 0) return null
  return (
    <div className="mb-3">
      <div className="eyebrow mb-2">{title}</div>
      <dl className="row mb-0" style={{ fontSize: '.87rem' }}>
        {Object.entries(data).map(([k, v]) => (
          <div className="col-md-6 d-flex gap-2 mb-1" key={k}>
            <dt className="text-muted fw-normal" style={{ minWidth: 150 }}>{k}</dt>
            <dd className="mb-0">{String(v)}</dd>
          </div>
        ))}
      </dl>
    </div>
  )
}

export default function LearnerRecord() {
  const { id } = useParams()
  const toast = useToast()
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [tab, setTab] = useState('overview')
  const [study, setStudy] = useState(null)
  const [call, setCall] = useState({ notes: '', nextGoal: '' })
  const [mentorHistory, setMentorHistory] = useState([])
  const [brief, setBrief] = useState(null)

  /* an admin-only endpoint is asked by admins only, not asked and then swallowed */
  const { user } = useAuth()
  const staff = user?.role === 'ADMIN' || user?.role === 'SUPER_ADMIN'

  /*
   * A failed load used to raise a toast and leave the page on its skeleton, so a refused
   * or missing record looked exactly like a slow one: it simply never finished loading,
   * with no way to tell why or to try again. The error is now part of the page.
   */
  const load = () => {
    setError(null)
    return api.get(`/mentor/learners/${id}`).then(setData).catch((e) => setError(e.message))
  }
  useEffect(() => { load() }, [id])

  /*
   * Who has mentored this learner, and why it changed. Switching a mentor off never
   * deletes them, precisely so this stays readable, and nothing was reading it. The
   * route is admin only, so a mentor opening the same record simply sees nothing.
   */
  useEffect(() => {
    if (!staff) { setMentorHistory([]); return }
    api.get(`/admin/learners/${id}/mentor-history`).then(setMentorHistory).catch(() => setMentorHistory([]))
  }, [id, staff])
  useEffect(() => {
    api.get(`/mentor/learners/${id}/study-time`).then(setStudy).catch(() => setStudy(null))
  }, [id])

  /*
   * The call brief. Everything below already lived in this record, spread across five
   * tabs, which meant a mentor opened five tabs before every progress call and read
   * them in a different order each time. Same data, in the order the conversation runs.
   */
  useEffect(() => {
    api.get(`/mentor/learners/${id}/brief`).then(setBrief).catch(() => setBrief(null))
  }, [id])

  if (error) {
    return (
      <Page title="Learner">
        <LoadError error={error} onRetry={load} />
      </Page>
    )
  }

  /* a failed load leaves data unset, so guard on the shape rather than on truthiness */
  if (!data?.overview) return <TableSkeleton />

  const o = data.overview
  const premium = o.trackType === 'PREMIUM'

  const logCall = async () => {
    try {
      await api.post(`/mentor/learners/${id}/call`, call)
      toast.push('Call logged. The next one is scheduled two weeks out.')
      setCall({ notes: '', nextGoal: '' })
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  return (
    <Page
      lede={`${premium ? 'Premium' : `Batch ${o.batch || ''}`} \u00b7 ${o.bundle || 'No course'}`}
      title={<span className="who-cell"><Avatar name={o.name} size={40} track={o.trackType} />{o.name}</span>}
      actions={
        data.contact?.phone
          ? <a className="btn btn-navy"
              href={`https://wa.me/${String(data.contact.phone).replace(/[^0-9]/g, '')}`}
              target="_blank" rel="noreferrer">WhatsApp</a>
          : null
      }
    >
      <TabBar active={tab} onChange={setTab} items={TABS.map(([key, label]) => ({ key, label }))} />

      {tab === 'overview' && (
        <>
          {brief && (
            <Card
              title="Before the call"
              eyebrow="Brief"
              note="Assembled from this record. Nothing here is new, it is just in the order you will need it."
              className={brief.atRisk ? 'brief is-risk' : 'brief'}
            >
              <div className="brief-grid">
                <div className="brief-cell">
                  <span className="brief-label">Where they are</span>
                  <strong className="mono brief-big">{brief.percent ?? 0}%</strong>
                  <span className="small muted">
                    {brief.idleDays == null ? 'Never signed in'
                      : brief.idleDays === 0 ? 'Signed in today'
                      : `Last signed in ${brief.idleDays} day${brief.idleDays === 1 ? '' : 's'} ago`}
                  </span>
                </div>

                <div className="brief-cell">
                  <span className="brief-label">Goal set last time</span>
                  {brief.lastGoal
                    ? <p className="brief-quote">{brief.lastGoal}</p>
                    : <span className="small muted">No goal recorded yet. Set one at the end of this call.</span>}
                  {brief.lastCallAt && (
                    <span className="small muted mono">{fmtDate(brief.lastCallAt)}</span>
                  )}
                </div>

                <div className="brief-cell">
                  <span className="brief-label">Waiting on you</span>
                  {brief.waiting?.length ? (
                    <ul className="brief-list">
                      {brief.waiting.slice(0, 4).map((w) => (
                        <li key={w.id}>
                          {w.title}
                          {w.status === 'CHANGES' && <Tag kind="wait">redo sent</Tag>}
                          {w.overdue && <Tag kind="stop">overdue</Tag>}
                        </li>
                      ))}
                    </ul>
                  ) : <span className="small muted">Nothing in the queue.</span>}
                </div>

                <div className="brief-cell">
                  <span className="brief-label">Tests below the pass mark</span>
                  {brief.weakTests?.length ? (
                    <ul className="brief-list">
                      {brief.weakTests.map((t, i) => (
                        <li key={i}>
                          <span className="mono">{Math.round(t.score)}</span> · {t.chapter}
                        </li>
                      ))}
                    </ul>
                  ) : <span className="small muted">Nothing failed. Ask what felt hardest instead.</span>}
                </div>

                <div className="brief-cell">
                  <span className="brief-label">Behind them</span>
                  <span className="small">
                    {brief.projectsApproved} project{brief.projectsApproved === 1 ? '' : 's'} approved
                    {' · '}
                    {brief.mocksDone} mock{brief.mocksDone === 1 ? '' : 's'} done
                  </span>
                  {brief.onHold && <Tag kind="wait">On hold</Tag>}
                  {brief.atRisk && <Tag kind="stop">Flagged at risk</Tag>}
                </div>
              </div>
            </Card>
          )}

          {study && (
            <Card
              title="Effort"
              note={study.trend === 'UP'
                ? 'Up on last week. Minutes rise before results do.'
                : 'Down on last week. Minutes fall before tasks do, which makes this the earliest signal you get.'}
            >
              <div className="effort">
                <div className="effort-figures">
                  <Trend
                    value={study.weekMinutes}
                    previous={study.previousWeekMinutes}
                    format={(v) => (v >= 60 ? `${Math.floor(v / 60)}h ${v % 60}m` : `${v}m`)}
                    label="this week"
                  />
                  <div className="small muted mt-1">
                    Active on {study.activeDaysLast14} of the last 14 days
                  </div>
                </div>
                <div className="effort-chart">
                  <Bars
                    data={study.last14}
                    height={80}
                    format={(v) => (v >= 60 ? `${Math.floor(v / 60)}h ${v % 60}m` : `${v}m`)}
                  />
                </div>
              </div>
            </Card>
          )}

          <Card title="Where they are">
            <div className="d-flex flex-wrap gap-2 mb-3">
              <Tag kind={data.gates.form ? 'ok' : 'wait'}>Information form</Tag>
              <Tag kind={data.gates.prereq ? 'ok' : 'wait'}>Prerequisite video</Tag>
              <Tag kind={data.gates.call ? 'ok' : 'wait'}>{premium ? 'Onboarding call' : 'Induction'}</Tag>
            </div>
            {data.progress.map((m) => (
              <div key={m.moduleId} className="d-flex align-items-center gap-3 py-2"
                style={{ borderBottom: '1px solid var(--line)' }}>
                <div style={{ minWidth: 170, fontWeight: 600, fontSize: '.9rem' }}>{m.name}</div>
                <Bar done={m.done} total={m.total} id={`rec-${m.moduleId}`} />
                {m.locked && <span className="text-muted" style={{ fontSize: '.8rem' }}>Locked</span>}
              </div>
            ))}
          </Card>
          <Card title="Contact">
            <div className="mono" style={{ fontSize: '.88rem' }}>
              {data.contact.email}<br />{data.contact.phone || '\u2014'}
            </div>
          </Card>
        </>
      )}

      {tab === 'learning' && <LearningTab data={data.learning} />}

      {tab === 'move' && (
        <>
          <MovePanel learnerId={id} onMoved={load} />
          {mentorHistory.length > 0 && (
            <Card title="Mentor history"
              note="Nobody is deleted when they are switched off, so every handover stays readable.">
              <table className="table table-pib mb-0">
                <thead><tr><th>When</th><th>From</th><th>To</th><th>Reason</th><th>By</th></tr></thead>
                <tbody>
                  {mentorHistory.map((h, i) => (
                    <tr key={i}>
                      <td className="mono">{fmtDate(h.at)}</td>
                      <td>{h.from || 'Unassigned'}</td>
                      <td>{h.to || '\u2014'}</td>
                      <td className="small">{h.reason || '\u2014'}</td>
                      <td className="mono small">{h.by || '\u2014'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Card>
          )}
        </>
      )}

      {tab === 'background' && (
        <Card title="Intake form" note="Everything the learner filled at onboarding.">
          {!data.background || Object.keys(data.background.sections || {}).length === 0 ? (
            <Empty title="Form not filled yet">Modules stay locked until this is complete.</Empty>
          ) : (
            <>
              {data.background.profileType && (
                <div className="mb-3">
                  <Tag kind="batch">
                    {data.background.profileType === 'CAREER_GAP' ? 'Returning after a career gap'
                      : data.background.profileType === 'WORKING' ? 'Working professional' : 'Fresher'}
                  </Tag>
                  {data.background.profileType === 'CAREER_GAP' && (
                    <div className="locked-note mt-2">
                      A gap changes the interview preparation, not just the roadmap. Raise it early,
                      not at the mock stage.
                    </div>
                  )}
                </div>
              )}
              {Object.entries(data.background.sections).map(([k, v]) => (
                <SectionDump key={k} title={k} data={v} />
              ))}
            </>
          )}
        </Card>
      )}

      {tab === 'resume' && (
        <Card
          title="Resume"
          note="Every version is kept. Marking one reviewed is what tells the learner somebody actually read it."
        >
          {(data.resumes || []).length === 0 ? (
            <Empty title="Nothing uploaded yet" icon="intake">
              A resume is asked for at onboarding and again before mocks.
            </Empty>
          ) : (
            <table className="table table-pib mb-0">
              <thead><tr><th>Version</th><th>Uploaded</th><th>Reviewed</th><th /></tr></thead>
              <tbody>
                {data.resumes.map((r) => (
                  <tr key={r.id}>
                    <td><strong>v{r.version}</strong></td>
                    <td className="mono">{fmtDate(r.uploadedAt)}</td>
                    <td>
                      {r.reviewedByMentor
                        ? <Tag kind="ok">Reviewed</Tag>
                        : <Tag kind="wait">Not yet</Tag>}
                    </td>
                    <td className="text-end">
                      {r.fileId && (
                        <button className="btn btn-quiet"
                          onClick={() => downloadFile(r.fileId, `resume-v${r.version}.pdf`)
                            .catch((e) => toast.push(e.message, 'bad'))}>
                          Open
                        </button>
                      )}
                      {!r.reviewedByMentor && (
                        <button className="btn btn-pib ms-2"
                          onClick={async () => {
                            try {
                              await api.post(`/mentor/resumes/${r.id}/reviewed`, {})
                              toast.push('Marked reviewed.')
                              await load()
                            } catch (e) { toast.push(e.message, 'bad') }
                          }}>
                          Mark reviewed
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
      )}

      {tab === 'assignments' && (
        <>
          <Card title="Tasks">
            {data.assignments.length === 0 ? <Empty title="Nothing submitted yet" /> : (
              <table className="table table-pib mb-0">
                <thead><tr><th>Task</th><th>Submitted</th><th>Status</th><th>Score</th></tr></thead>
                <tbody>
                  {data.assignments.map((a) => (
                    <tr key={a.id}>
                      <td>{a.title}</td>
                      <td className="mono">{fmtDate(a.submittedAt)}</td>
                      <td><StatusTag status={a.status} /></td>
                      <td className="mono">{a.score ?? '\u2014'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>
          <Card title="Projects">
            {data.projects.length === 0 ? <Empty title="No projects yet" /> : (
              <table className="table table-pib mb-0">
                <thead><tr><th>Project</th><th>Submitted</th><th>Status</th></tr></thead>
                <tbody>
                  {data.projects.map((p) => (
                    <tr key={p.id}>
                      <td>{p.title}</td>
                      <td className="mono">{fmtDate(p.submittedAt)}</td>
                      <td><StatusTag status={p.status} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>
        </>
      )}

      {tab === 'tests' && (
        <Card title="Test attempts" note="The first attempt on each chapter is the recorded score.">
          {data.tests.length === 0 ? <Empty title="No tests taken yet" /> : (
            <table className="table table-pib mb-0">
              <thead><tr><th>Taken</th><th>Attempt</th><th>Score</th></tr></thead>
              <tbody>
                {data.tests.map((t) => (
                  <tr key={t.id}>
                    <td className="mono">{fmtDateTime(t.takenAt)}</td>
                    <td className="mono">#{t.attemptNo}</td>
                    <td className="mono">{t.score}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
      )}

      {tab === 'activity' && (
        <Card title="Mock interviews and sessions">
          {data.mocks.length === 0 ? <Empty title="No mocks raised" /> : (
            <table className="table table-pib mb-0">
              <thead><tr><th>Raised</th><th>Origin</th><th>Status</th><th>Scheduled</th><th>Score</th></tr></thead>
              <tbody>
                {data.mocks.map((m) => (
                  <tr key={m.id}>
                    <td className="mono">{fmtDate(m.createdAt)}</td>
                    <td>{m.origin === 'AUTO' ? 'Module complete' : 'Requested'}</td>
                    <td><StatusTag status={m.status} /></td>
                    <td className="mono">{m.scheduledFor ? fmtDateTime(m.scheduledFor) : '\u2014'}</td>
                    <td className="mono">{m.score ?? '\u2014'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
      )}

      {tab === 'notes' && (
        <Card
          title={premium ? 'Progress calls' : 'Progress calls do not run for batch'}
          note={premium
            ? 'Logging a call schedules the next one two weeks out.'
            : 'At group scale, drift is tracked from activity instead. See the at risk list.'}
        >
          {premium ? (
            <>
              <div className="row g-2 align-items-end mb-3">
                <div className="col-md-6">
                  <label className="form-label">What was discussed</label>
                  <input className="form-control" value={call.notes}
                    onChange={(e) => setCall((c) => ({ ...c, notes: e.target.value }))} />
                </div>
                <div className="col-md-4">
                  <label className="form-label">Next goal</label>
                  <input className="form-control" value={call.nextGoal}
                    onChange={(e) => setCall((c) => ({ ...c, nextGoal: e.target.value }))} />
                </div>
                <div className="col-md-2">
                  <button className="btn btn-pib w-100" onClick={logCall} disabled={!call.notes}>Log call</button>
                </div>
              </div>
              {data.calls.length === 0 ? <Empty title="No calls logged yet" /> : (
                <table className="table table-pib mb-0">
                  <thead><tr><th>Scheduled</th><th>Held</th><th>Notes</th><th>Next goal</th></tr></thead>
                  <tbody>
                    {data.calls.map((c) => (
                      <tr key={c.id}>
                        <td className="mono">{fmtDateTime(c.scheduledFor)}</td>
                        <td className="mono">{c.heldAt ? fmtDateTime(c.heldAt) : 'Upcoming'}</td>
                        <td>{c.notes || '\u2014'}</td>
                        <td>{c.nextGoal || '\u2014'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </>
          ) : (
            <Empty title="Tracked from activity">
              Sign ins, videos watched, tests taken and tasks submitted feed the at risk list.
            </Empty>
          )}
        </Card>
      )}
    </Page>
  )
}

/* ------------------------------------------------------------------ learning */

const STATE_LABEL = {
  DONE: 'Watched',
  NEARLY: 'Almost finished',
  STARTED: 'Started',
  NOT_STARTED: 'Not opened'
}

function mins(v) {
  if (!v) return '0m'
  return v >= 60 ? `${Math.floor(v / 60)}h ${v % 60}m` : `${v}m`
}

/**
 * What the learner has actually done, video by video.
 *
 * The record used to draw one bar per module, which cannot answer the question a mentor
 * asks first. Not opened, started and abandoned, almost finished and watched are four
 * different conversations, and the product knew which was which all along without ever
 * showing anyone.
 */
function LearningTab({ data }) {
  const [open, setOpen] = useState(() => new Set())

  if (!data?.summary?.hasCourse) {
    return (
      <Empty title="No course assigned">
        Nothing to track until a course is set on their record.
      </Empty>
    )
  }

  const s = data.summary
  const att = data.attendance || {}
  const toggle = (id) => setOpen((prev) => {
    const next = new Set(prev)
    next.has(id) ? next.delete(id) : next.add(id)
    return next
  })

  return (
    <>
      <Card title="Where they actually are">
        <div className="learn-summary">
          <div>
            <span className="brief-label">Videos watched</span>
            <strong className="mono brief-big">{s.topicsWatched}<em>/{s.totalTopics}</em></strong>
            <span className="small muted">{s.percent}% of the course</span>
          </div>
          <div>
            <span className="brief-label">Opened, not finished</span>
            <strong className="mono brief-big">{s.topicsStarted}</strong>
            <span className="small muted">abandoned part way through</span>
          </div>
          <div>
            <span className="brief-label">Never opened</span>
            <strong className="mono brief-big">{s.topicsUntouched}</strong>
            <span className="small muted">{mins(s.totalMinutes - s.watchedMinutes)} of video left</span>
          </div>
          <div>
            <span className="brief-label">Last watched</span>
            <strong className="brief-big">
              {s.daysSinceWatched == null ? 'Never'
                : s.daysSinceWatched === 0 ? 'Today' : `${s.daysSinceWatched}d ago`}
            </strong>
            <span className="small muted">{mins(s.watchedMinutes)} watched in total</span>
          </div>
          <div>
            <span className="brief-label">Sessions attended</span>
            <strong className="mono brief-big">
              {att.rate == null ? '\u2014' : `${att.rate}%`}
            </strong>
            <span className="small muted">
              {att.present || 0} present, {att.late || 0} late, {att.absent || 0} missed
            </span>
          </div>
        </div>
      </Card>

      {data.modules.map((m) => (
        <Card key={m.id} title={m.name} note={`${m.topicsWatched} of ${m.topicCount} videos watched`}>
          <div className="learn-mod-bar"><i style={{ width: `${m.percent}%` }} /></div>

          {m.chapters.map((c) => {
            const isOpen = open.has(c.id)
            return (
              <div className={`learn-ch ${c.watched ? 'is-done' : ''}`} key={c.id}>
                <button className="learn-ch-head" onClick={() => toggle(c.id)} aria-expanded={isOpen}>
                  <span className="mono learn-ch-no">{String(c.position).padStart(2, '0')}</span>
                  <span className="learn-ch-title">{c.title}</span>

                  <span className="learn-ch-meta">
                    <span className="mono">{c.topicsWatched}/{c.topicCount} videos</span>
                    {c.topicsStarted > 0 && <Tag kind="wait">{c.topicsStarted} part done</Tag>}
                    {c.hasTest && (
                      <Tag kind={c.quizScore == null ? 'wait' : c.quizScore >= 60 ? 'ok' : 'stop'}>
                        {c.quizScore == null ? 'Test not taken' : `Test ${Math.round(c.quizScore)}`}
                      </Tag>
                    )}
                    {c.hasTeachback && c.conceptCheckScore != null && (
                      <Tag kind={c.conceptCheckScore >= 60 ? 'ok' : 'stop'}>
                        Teach back {Math.round(c.conceptCheckScore)}
                      </Tag>
                    )}
                    {/* answered, but the assistant was unreachable, so nobody has read it */}
                    {c.hasTeachback && c.conceptCheckPending && (
                      <Tag kind="wait" title={c.conceptCheckAnswer || ''}>
                        Teach back ungraded
                      </Tag>
                    )}
                    {c.hasAssignment && <StatusTag status={c.taskStatus} />}
                    {c.hasNotes && <Tag>Notes</Tag>}
                  </span>

                  <Icon name="chevron" className={isOpen ? 'rot' : ''} size={15} />
                </button>

                {isOpen && (
                  <ul className="learn-topics">
                    {c.topics.length === 0 && <li className="muted small">No videos in this chapter yet.</li>}
                    {c.topics.map((t) => (
                      <li key={t.id} className={`learn-topic st-${t.state.toLowerCase()}`}>
                        <span className="learn-dot" aria-hidden="true" />
                        <span className="learn-topic-title">{t.title}</span>
                        <span className="learn-topic-state">{STATE_LABEL[t.state]}</span>
                        <span className="learn-topic-bar">
                          <i style={{ width: `${t.watchedPercent}%` }} />
                        </span>
                        <span className="mono learn-topic-pct">
                          {t.state === 'NOT_STARTED' ? '\u2014' : `${t.watchedPercent}%`}
                        </span>
                        <span className="mono learn-topic-when">
                          {t.lastWatchedAt ? fmtDate(t.lastWatchedAt) : ''}
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            )
          })}
        </Card>
      ))}

      {data.doubts?.length > 0 && (
        <Card title="What they have been asking" note="Questions put to the assistant, newest first.">
          <ul className="learn-doubts">
            {data.doubts.map((d, i) => (
              <li key={i}>
                <span className="mono small muted">{fmtDate(d.at)}</span>
                <span>{d.question}</span>
              </li>
            ))}
          </ul>
        </Card>
      )}

      {att.recent?.length > 0 && (
        <Card title="Recent sessions">
          <div className="table-responsive">
            <table className="table table-pib mb-0">
              <thead><tr><th>When</th><th>Session</th><th>Attendance</th></tr></thead>
              <tbody>
                {att.recent.map((r, i) => (
                  <tr key={i}>
                    <td className="mono small">{fmtDateTime(r.at)}</td>
                    <td>{r.topic || r.kind || 'Session'}</td>
                    <td>
                      <Tag kind={r.state === 'PRESENT' ? 'ok' : r.state === 'LATE' ? 'wait' : 'stop'}>
                        {r.state === 'PRESENT' ? 'Present' : r.state === 'LATE' ? 'Late' : 'Missed'}
                      </Tag>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </>
  )
}
