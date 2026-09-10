import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { useDialog } from '../../components/Dialog'
import Avatar from '../../components/Avatar'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, Page, Stat, Tag, fmtDate } from '../../components/Ui'

/**
 * Cover, for when a mentor is away.
 *
 * Nothing is reassigned. The learner's mentor stays their mentor, the record still says
 * so, and the covering mentor simply sees the same queues until the dates run out. A
 * five day absence should not rewrite a relationship that took months to build.
 */
export default function Cover() {
  const toast = useToast()
  const { ask } = useDialog()
  const [rows, setRows] = useState(null)
  const [mentors, setMentors] = useState([])

  const load = async () => {
    setRows(await api.get('/admin/cover'))
    setMentors(await api.get('/admin/mentors').then((p) => p.filter((x) => x.active !== false))
      .catch(() => []))
  }
  useEffect(() => { load() }, [])
  if (!rows) return <TableSkeleton />

  const arrange = async () => {
    const r = await ask({
      title: 'Arrange cover',
      body: 'The learners stay with their own mentor. Whoever covers sees the same queues '
          + 'and records until the dates run out, and everything they do is under their own name.',
      fields: [
        { name: 'mentorId', label: 'Who is away', required: true,
          options: mentors.map((m) => ({ value: m.id, label: `${m.name} · ${m.learners} learners` })) },
        { name: 'coveringMentorId', label: 'Who is covering', required: true,
          options: mentors.map((m) => ({ value: m.id, label: `${m.name} · ${m.learners} learners` })),
          hint: 'They keep their own learners as well, so check the load.' },
        { name: 'from', label: 'From', type: 'date', required: true },
        { name: 'until', label: 'Until', type: 'date', required: true },
        /* the box took random characters and numbers, and a reason nobody can group by
           is a reason nobody reads. The list is the set of things that actually happen. */
        { name: 'reason', label: 'Reason', required: true,
          options: [
            { value: 'Planned leave', label: 'Planned leave' },
            { value: 'Sick leave', label: 'Sick leave' },
            { value: 'Travel', label: 'Travel' },
            { value: 'Working from home', label: 'Working from home' },
            { value: 'Training', label: 'Training or conference' },
            { value: 'Other', label: 'Something else' }
          ],
          placeholder: 'Choose one' },
        { name: 'note', label: 'Anything to add', placeholder: 'Optional, kept on the record' }
      ],
      confirmLabel: 'Arrange cover'
    })
    if (!r) return
    try {
      await api.post('/admin/cover', {
        ...r,
        reason: r.note && r.note.trim() ? `${r.reason} - ${r.note.trim()}` : r.reason
      })
      toast.push('Cover arranged. It ends on its own date.')
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const end = async (c) => {
    try {
      await api.post(`/admin/cover/${c.id}/end`, {})
      toast.push('Cover ended.')
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const running = rows.filter((r) => r.running)

  return (
    <Page
      title="Cover"
      lede="Who is covering for whom while a mentor is away."
      actions={<button className="btn btn-pib" onClick={arrange}>Arrange cover</button>}
    >
      <div className="row g-3 mb-4">
        <div className="col-6 col-lg-4"><Stat value={running.length} label="Running now" icon="batch" /></div>
        <div className="col-6 col-lg-4">
          <Stat value={running.reduce((n, r) => n + r.learners, 0)} label="Learners covered" icon="queues" />
        </div>
        <div className="col-12 col-lg-4"><Stat value={mentors.length} label="Mentors" icon="people" /></div>
      </div>

      <Card note="Nothing is reassigned. The learner's mentor does not change, and the learner sees no difference.">
        {rows.length === 0 ? (
          <Empty title="No cover arranged" icon="batch">
            When a mentor is away, this is where their learners are looked after.
          </Empty>
        ) : (
          <table className="table table-pib stacked mb-0">
            <thead>
              <tr><th>Away</th><th>Covering</th><th>Learners</th><th>Dates</th>
                <th>Reason</th><th /></tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id}>
                  <td data-label="Away">
                    <span className="who-cell"><Avatar name={c.mentor} size={30} />{c.mentor}</span>
                  </td>
                  <td data-label="Covering">
                    <span className="who-cell"><Avatar name={c.covering} size={30} />{c.covering}</span>
                  </td>
                  <td data-label="Learners" className="mono">{c.learners}</td>
                  <td data-label="Dates" className="mono small">{fmtDate(c.from)} to {fmtDate(c.until)}</td>
                  <td data-label="Reason" className="small">{c.reason}</td>
                  <td className="text-end">
                    {c.running
                      ? <>
                          <Tag kind="ok">Running</Tag>
                          <button className="btn btn-quiet ms-2" onClick={() => end(c)}>End now</button>
                        </>
                      : c.active
                        ? <Tag kind="batch">Scheduled</Tag>
                        : <span className="small muted">Finished</span>}
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
