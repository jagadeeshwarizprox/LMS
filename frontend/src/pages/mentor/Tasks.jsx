import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { useDialog } from '../../components/Dialog'
import Avatar from '../../components/Avatar'
import Drawer from '../../components/Drawer'
import TaskThread from '../../components/TaskThread'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, LoadError, Page, Stat, Tag, fmtDate } from '../../components/Ui'

/**
 * Setting work, and everything late.
 *
 * A mentor could only ever review work a chapter had already generated. Now they can
 * set a task for one learner or a whole batch, with a due date that the at-risk sweep
 * has been waiting for since the beginning.
 */
export default function Tasks() {
  const toast = useToast()
  const { ask } = useDialog()
  const [overdue, setOverdue] = useState(null)
  const [queue, setQueue] = useState([])
  const [roster, setRoster] = useState(null)
  const [rubrics, setRubrics] = useState([])
  const [open, setOpen] = useState(null)
  const [picked, setPicked] = useState([])

  const [error, setError] = useState(null)

  /*
   * Four sequential awaits with no catch: the first one to fail left overdue or roster
   * null and the page sat on a skeleton forever with nothing on screen saying why.
   * Reported as "it does not load", which is precisely what it did.
   */
  const load = async () => {
    try {
      setOverdue(await api.get('/mentor/tasks/overdue'))
      const dash = await api.get('/mentor/dashboard')
      setQueue(dash.taskQueue || [])
      setRoster(await api.get('/mentor/roster'))
      setRubrics(await api.get('/mentor/rubrics').catch(() => []))
      setError(null)
    } catch (e) {
      setError(e.message)
    }
  }
  useEffect(() => { load() }, [])
  if (error) {
    return <Page title="Tasks and projects"><LoadError error={error} onRetry={load} /></Page>
  }

  /* one entry per chapter, each group sorted oldest first, and the groups themselves
     ordered by whoever has been waiting longest */
  const groupedQueue = Object.entries(
    queue.reduce((acc, t) => {
      const key = t.chapter || t.title || 'Other work'
      ;(acc[key] = acc[key] || []).push({
        ...t,
        waitingDays: t.submittedAt
          ? Math.floor((Date.now() - new Date(t.submittedAt)) / 86400000)
          : 0
      })
      return acc
    }, {})
  )
    .map(([k, rows]) => [k, rows.sort((a, b) => new Date(a.submittedAt) - new Date(b.submittedAt))])
    .sort((a, b) => new Date(a[1][0].submittedAt) - new Date(b[1][0].submittedAt))
  if (!overdue || !roster) return <TableSkeleton />

  const everyone = roster.groups.flatMap((g) => g.learners)

  /*
   * The same picker, used to say something rather than set something. One message to
   * several learners was already served and never called, so a mentor with the same
   * thing to tell six people sent it six times.
   */
  const broadcast = async () => {
    const r = await ask({
      title: `Message ${picked.length} learner${picked.length === 1 ? '' : 's'}`,
      body: 'Each one gets it on their own messages page, not as a group thread, so nobody '
          + 'sees who else was picked.',
      fields: [
        { name: 'subject', label: 'Subject', required: true, placeholder: 'What this is about' },
        { name: 'body', label: 'Message', required: true, multiline: true }
      ],
      confirmLabel: 'Send'
    })
    if (!r) return
    try {
      const out = await api.post('/mentor/broadcast', {
        learnerIds: picked, subject: r.subject.trim(), body: r.body.trim()
      })
      toast.push(`Sent to ${out.sent} learner${out.sent === 1 ? '' : 's'}.`)
      setPicked([])
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const assign = async () => {
    const r = await ask({
      title: 'Set a task',
      body: picked.length
        ? `Going to ${picked.length} selected learner${picked.length > 1 ? 's' : ''}.`
        : 'Choose a batch below, or pick learners from the list first.',
      fields: [
        { name: 'title', label: 'Title', required: true, placeholder: 'What are they doing' },
        { name: 'brief', label: 'Brief', multiline: true, required: true,
          placeholder: 'What good looks like, and what to hand in',
          hint: 'This is what they see. Be specific enough to act on.' },
        { name: 'dueAt', label: 'Due', type: 'date' },
        ...(picked.length ? [] : [{
          name: 'batchId', label: 'Batch', required: true,
          options: roster.groups.filter((g) => g.label.startsWith('Batch'))
            .map((g) => ({ value: g.learners[0]?.batch, label: `${g.label} · ${g.count} learners` }))
        }]),
        ...(rubrics.length ? [{
          name: 'rubricId', label: 'Rubric',
          options: rubrics.map((x) => ({ value: x.id, label: x.name })),
          hint: 'Named criteria, so two mentors score the same work the same way.'
        }] : [])
      ],
      confirmLabel: 'Set the task'
    })
    if (!r) return
    try {
      const res = await api.post('/mentor/tasks', {
        title: r.title,
        brief: r.brief,
        dueAt: r.dueAt ? new Date(`${r.dueAt}T18:00`).toISOString() : null,
        rubricId: r.rubricId || null,
        learnerIds: picked,
        batchId: r.batchId || null
      })
      toast.push(`Set for ${res.created} learner${res.created > 1 ? 's' : ''}. They have been told.`)
      setPicked([])
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const bulkApprove = async () => {
    const ids = queue.map((t) => t.id)
    if (ids.length === 0) return
    const r = await ask({
      title: `Approve ${ids.length} tasks`,
      body: 'One comment goes to all of them. Anything needing individual feedback should be '
          + 'reviewed on its own.',
      fields: [{ name: 'feedback', label: 'Comment', multiline: true, placeholder: 'Optional' }],
      confirmLabel: 'Approve all'
    })
    if (!r) return

    /* the queue empties immediately; the request follows if they leave it alone */
    const before = queue
    setQueue([])
    toast.pushUndo({
      message: `${ids.length} approved.`,
      commit: async () => {
        await api.post('/mentor/tasks/bulk-review',
          { ids, status: 'APPROVED', feedback: r.feedback })
        await load()
      },
      revert: () => setQueue(before)
    })
  }

  const toggle = (id) => setPicked((p) => p.includes(id) ? p.filter((x) => x !== id) : [...p, id])

  return (
    <Page
      title="Tasks"
      lede="Assignments and projects waiting on your review."
      actions={
        <>
          {queue.length > 1 && (
            <button className="btn btn-quiet" onClick={bulkApprove}>Approve all waiting</button>
          )}
          <button className="btn btn-pib" onClick={assign}>
            {picked.length ? `Set a task for ${picked.length}` : 'Set a task'}
          </button>
        </>
      }
    >
      <div className="row g-3 mb-4">
        <div className="col-6 col-lg-3"><Stat value={queue.length} label="Waiting on me" icon="queues" /></div>
        <div className="col-6 col-lg-3">
          <Stat value={overdue.length} label="Overdue" icon="risk"
            tone={overdue.length ? 'var(--stop)' : undefined} />
        </div>
        <div className="col-6 col-lg-3"><Stat value={everyone.length} label="Learners" icon="batch" /></div>
        <div className="col-6 col-lg-3"><Stat value={rubrics.length} label="Rubrics" icon="features" /></div>
      </div>

      <Card title="Overdue" note="Due dates finally do something: this is what the at-risk sweep counts.">
        {overdue.length === 0 ? (
          <Empty title="Nothing is late" icon="check">Every task with a due date is on time.</Empty>
        ) : (
          <table className="table table-pib mb-0">
            <thead><tr><th>Learner</th><th>Task</th><th>Due</th><th>Late by</th><th /></tr></thead>
            <tbody>
              {overdue.map((t) => (
                <tr key={t.id}>
                  <td>
                    <span className="who-cell">
                      <Avatar name={t.learner} size={30} />{t.learner}
                    </span>
                  </td>
                  <td>{t.title}</td>
                  <td className="mono">{fmtDate(t.dueAt)}</td>
                  <td><Tag kind="stop">{t.daysLate}d</Tag></td>
                  <td className="text-end">
                    <button className="btn btn-quiet" onClick={() => setOpen(t)}>Open</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      {/*
        * Grouped by chapter, oldest first.
        *
        * A flat list of learner and task title said nothing about what was being marked,
        * so a mentor opened each row to find out. Marking eight submissions of the same
        * assignment in a row is one job done consistently; marking eight different ones
        * interleaved is eight context switches, and the scores drift apart.
        */}
      <Card title="Waiting on me" note="Grouped by assignment, longest waiting first">
        {queue.length === 0 ? <Empty title="Queue is clear" icon="check" /> : (
          groupedQueue.map(([chapter, rows]) => (
            <div className="queue-group" key={chapter}>
              <div className="queue-group-head">
                <span>{chapter}</span>
                <span className="tiny muted">{rows.length} waiting</span>
              </div>
              <table className="table table-pib mb-0">
                <thead>
                  <tr><th>Learner</th><th>Submitted</th><th>Attempt</th><th /></tr>
                </thead>
                <tbody>
                  {rows.map((t) => (
                    <tr key={t.id}>
                      <td>
                        <span className="who-cell">
                          <Avatar name={t.learner} size={30} />{t.learner}
                        </span>
                      </td>
                      <td className="mono">
                        {fmtDate(t.submittedAt)}
                        {t.waitingDays >= 2 && <Tag kind="wait">{t.waitingDays}d</Tag>}
                      </td>
                      <td className="mono">
                        {/* a third attempt at the same task is a different conversation
                            from a first, and the mentor should know before opening it */}
                        {t.submissionCount > 1 ? `#${t.submissionCount}` : '\u2014'}
                      </td>
                      <td className="text-end">
                        <button className="btn btn-quiet" onClick={() => setOpen(t)}>Review</button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ))
        )}
      </Card>

      <Card title="Choose who gets the next task"
        note="Pick learners here, then set a task or send a message. Leave everything unpicked to send a task to a whole batch."
        actions={
          <button className="btn btn-quiet" onClick={broadcast} disabled={picked.length === 0}>
            Message {picked.length || 'the'} picked
          </button>
        }
      >
        <div className="picker">
          {everyone.map((l) => (
            <button
              key={l.learnerId}
              className={`pick ${picked.includes(l.learnerId) ? 'on' : ''}`}
              onClick={() => toggle(l.learnerId)}
            >
              <Avatar name={l.name} size={26} track={l.trackType} />
              {l.name}
            </button>
          ))}
        </div>
      </Card>

      <Drawer
        open={Boolean(open)}
        title={open?.title || ''}
        subtitle={open?.learner}
        onClose={() => setOpen(null)}
      >
        {open && <TaskThread assignmentId={open.id} role="MENTOR" onChanged={load} />}
      </Drawer>
    </Page>
  )
}
