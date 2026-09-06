import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { useDialog } from '../../components/Dialog'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, LoadError, Page, Tag, fmtDate } from '../../components/Ui'

export default function Batches() {
  const toast = useToast()
  const { ask } = useDialog()
  const [rows, setRows] = useState(null)
  const [draft, setDraft] = useState({
    code: '', name: '', startDate: '', whatsappLink: '', mentorId: ''
  })
  const [mentors, setMentors] = useState([])

  const [loadError, setLoadError] = useState(null)

  const load = () => api.get('/admin/batches')
    .then((r) => { setRows(r); setLoadError(null) })
    .catch((e) => setLoadError(e.message))

  useEffect(() => {
    api.get('/admin/mentors')
      .then((m) => setMentors(m.filter((x) => x.active !== false)))
      .catch(() => setMentors([]))
  }, [])
  useEffect(() => { load() }, [])
  if (loadError) {
    return <Page title="Batches"><LoadError error={loadError} onRetry={load} /></Page>
  }
  if (!rows) return <TableSkeleton />

  const create = async () => {
    try {
      await api.post('/admin/batches', { ...draft, startDate: draft.startDate || null })
      toast.push('Batch created.')
      setDraft({ code: '', name: '', startDate: '', whatsappLink: '', mentorId: '' })
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const closeInduction = async (id) => {
    const r = await ask({
      title: 'Mark the induction as held',
      body: 'Every learner in this batch has their induction gate cleared, and their modules open. '
          + 'Anyone who joins later watches the recording instead of a second live session.',
      fields: [{
        name: 'recordingUrl', label: 'Recording link', placeholder: 'Optional, but mid batch joiners need it'
      }],
      confirmLabel: 'Mark as held'
    })
    if (!r) return
    const url = r.recordingUrl
    try {
      await api.post(`/admin/batches/${id}/induction-done`, { recordingUrl: url })
      toast.push('Induction marked done. The cohort modules are open.')
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  /*
   * Batch surfaces in the LMS are read only except for this one. Announcements are
   * what a batch learner sees in place of the progress calls premium gets, and the
   * route to write one has been sitting here uncalled.
   */
  const announce = async (b) => {
    const r = await ask({
      title: `Announcement to ${b.code}`,
      body: 'Every learner in this batch sees it on their batch page. It does not go to '
          + 'WhatsApp, so keep anything urgent in the group as well.',
      fields: [{
        name: 'body', label: 'What they need to know', required: true, multiline: true,
        placeholder: 'Session moved, dataset reposted, deadline changed'
      }],
      confirmLabel: 'Post it'
    })
    if (!r) return
    try {
      await api.post('/admin/announcements', { batchId: b.id, body: r.body.trim() })
      toast.push(`Posted to ${b.code}.`)
    } catch (e) { toast.push(e.message, 'bad') }
  }

  /*
   * A batch could not be edited after it was made. If the mentor left, every learner had
   * to be moved one at a time. Changing it here moves the whole cohort, because that is
   * what a batch mentor means.
   */
  const edit = async (b) => {
    const r = await ask({
      title: `Edit ${b.code}`,
      body: 'Changing the mentor moves every learner in this batch to them. Anything left '
          + 'blank is unchanged.',
      fields: [
        { name: 'name', label: 'Name', placeholder: b.name || '' },
        {
          name: 'mentorId',
          label: 'Mentor',
          options: mentors.map((m) => ({ value: m.id, label: m.name })),
          placeholder: `Keep ${b.mentor || 'as is'}`
        },
        { name: 'startDate', label: 'Start date', type: 'date' },
        { name: 'whatsappLink', label: 'WhatsApp link', placeholder: b.whatsappLink || '' }
      ],
      confirmLabel: 'Save'
    })
    if (!r) return
    /* only send what was actually filled in: an empty box means "leave it alone",
       not "blank it" */
    const patch = Object.fromEntries(
      Object.entries(r).filter(([, v]) => String(v || '').trim() !== '')
    )
    if (Object.keys(patch).length === 0) return
    try {
      await api.post(`/admin/batches/${b.id}`, patch)
      toast.push(`${b.code} updated.`)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  /*
   * Closing is about intake only. Nobody is removed and nothing is archived: the batch
   * stops being somewhere new learners can land, which is what keeps the Wednesday
   * placement rule and every batch dropdown from growing for ever.
   */
  const setOpen = async (b, open) => {
    if (!open) {
      const r = await ask({
        title: `Close ${b.code} to new joiners?`,
        body: 'The cohort carries on exactly as it is. Nobody is removed, no progress is '
            + 'touched, and mentors keep their learners. It only stops appearing as a '
            + 'destination for new placements.',
        confirmLabel: 'Close it'
      })
      if (!r) return
    }
    try {
      await api.post(`/admin/batches/${b.id}/open`, { open })
      toast.push(open ? `${b.code} reopened.` : `${b.code} closed to new joiners.`)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const set = (k) => (e) => setDraft((d) => ({ ...d, [k]: e.target.value }))

  return (
    <Page title="Batches"
      lede="One batch starts every week. A late joiner can be placed in the running one.">
      <Card title="Create a batch"
        note="Batches start on a Tuesday. Leave the date empty to use the next one. The mentor is fixed here and every learner placed in this batch becomes theirs.">
        <div className="row g-2 align-items-end">
          <div className="col-md-2">
            <label className="form-label">Code</label>
            <input className="form-control mono" placeholder="B58" value={draft.code} onChange={set('code')} />
          </div>
          <div className="col-md-3">
            <label className="form-label">Name</label>
            <input className="form-control" value={draft.name} onChange={set('name')} />
          </div>
          <div className="col-md-3">
            <label className="form-label">Mentor</label>
            <select className="form-select" value={draft.mentorId} onChange={set('mentorId')}>
              <option value="">Choose a mentor</option>
              {mentors.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.name}{m.learners != null ? ` \u00b7 ${m.learners} learners` : ''}
                </option>
              ))}
            </select>
          </div>
          <div className="col-md-2">
            <label className="form-label">Start date</label>
            <input className="form-control" type="date" value={draft.startDate} onChange={set('startDate')} />
          </div>
          <div className="col-md-2">
            <label className="form-label">WhatsApp link</label>
            <input className="form-control" value={draft.whatsappLink} onChange={set('whatsappLink')} />
          </div>
          <div className="col-12">
            <button className="btn btn-pib" onClick={create}
              disabled={!draft.code || !draft.mentorId}>Create the batch</button>
          </div>
        </div>
      </Card>

      <Card title="All batches">
        {rows.length === 0 ? <Empty title="No batches yet" /> : (
          <table className="table table-pib mb-0">
            <thead><tr><th>Code</th><th>Mentor</th><th>Course</th><th>Starts</th><th>Learners</th>
              <th>Induction</th><th>Intake</th><th /></tr></thead>
            <tbody>
              {rows.map((b) => (
                <tr key={b.id}>
                  <td className="mono">{b.code}</td>
                  <td>{b.mentor || <span className="muted">Nobody yet</span>}</td>
                  <td>{b.bundle || '\u2014'}</td>
                  <td className="mono">{fmtDate(b.startDate)}</td>
                  <td className="mono">{b.size}</td>
                  <td>
                    {b.inductionDone
                      ? <Tag kind="ok">Held</Tag>
                      : <Tag kind="wait">{fmtDate(b.inductionDate)}</Tag>}
                  </td>
                  <td>
                    {b.open ? <Tag kind="ok">Open</Tag> : <Tag kind="batch">Closed</Tag>}
                  </td>
                  <td className="text-end d-flex gap-2 justify-content-end">
                    {!b.inductionDone && (
                      <button className="btn btn-quiet" onClick={() => closeInduction(b.id)}>Mark induction done</button>
                    )}
                    <button className="btn btn-quiet" onClick={() => edit(b)}>Edit</button>
                    <button className="btn btn-quiet" onClick={() => setOpen(b, !b.open)}>
                      {b.open ? 'Close' : 'Reopen'}
                    </button>
                    <button className="btn btn-quiet" onClick={() => announce(b)}>Announce</button>
                    {b.whatsappLink && (
                      <a className="btn btn-quiet" href={b.whatsappLink} target="_blank" rel="noreferrer">Group</a>
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
