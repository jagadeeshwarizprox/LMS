import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { TableSkeleton } from '../../components/Skeletons'
import { useDialog } from '../../components/Dialog'
import { Card, Empty, Page, Tag, fmtDateTime } from '../../components/Ui'

export default function MentorSlots() {
  const toast = useToast()
  const { ask } = useDialog()
  const [rows, setRows] = useState(null)
  const [draft, setDraft] = useState({
    kind: 'ONBOARDING', startsAt: '', durationMin: 30, capacity: 1, trackScope: 'PREMIUM', joinUrl: ''
  })

  const [room, setRoom] = useState({ joinUrl: '', passcode: '' })
  const [rec, setRec] = useState({ title: '', externalId: '', slotId: '', notes: '' })
  const [check, setCheck] = useState(null)

  const load = async () => {
    setRows(await api.get('/mentor/slots'))
    const r = await api.get('/mentor/room')
    if (r.set) setRoom({ joinUrl: r.joinUrl, passcode: r.passcode })
  }
  useEffect(() => { load() }, [])
  if (!rows) return <TableSkeleton />

  /* whether a session is joinable depends on a room, a link on it, the time window and
     the track scope, and each can be fine while a learner still cannot get in */
  const runCheck = async () => {
    setCheck('running')
    try { setCheck(await api.get('/slots/join-check')) }
    catch (e) { toast.push(e.message, 'bad'); setCheck(null) }
  }

  const saveRoom = async () => {
    try {
      await api.post('/mentor/room', room)
      toast.push('Room saved. Every session of yours uses it.')
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const publish = async () => {
    try {
      await api.post('/mentor/recordings', rec)
      toast.push('Recording published to the learners who were in that session.')
      setRec({ title: '', externalId: '', slotId: '', notes: '' })
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const set = (k) => (e) => setDraft((d) => ({ ...d, [k]: e.target.value }))

  const release = async () => {
    try {
      await api.post('/mentor/slots', {
        ...draft,
        durationMin: Number(draft.durationMin),
        capacity: Number(draft.capacity),
        startsAt: new Date(draft.startsAt).toISOString()
      })
      toast.push('Slot released to learners.')
      setDraft((d) => ({ ...d, startsAt: '', joinUrl: '' }))
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  /**
   * Cancelling tells everyone who booked, which is why it asks for a reason: the
   * reason is the message they get, and "cancelled" on its own helps nobody.
   */
  const cancel = async (r) => {
    const res = await ask({
      title: 'Cancel this session',
      intent: 'danger',
      body: `${r.booked.length} learner${r.booked.length === 1 ? ' has' : 's have'} booked. `
          + 'They are told straight away, and what you write here is what they read.',
      fields: [{
        name: 'reason', label: 'Why', required: true,
        placeholder: 'Unwell, will run it again on Thursday'
      }],
      confirmLabel: 'Cancel the session'
    })
    if (!res) return
    try {
      await api.post(`/mentor/slots/${r.slot.id}/cancel`, { reason: res.reason })
      toast.push('Cancelled. Everyone booked has been told.')
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  /** Moving keeps the bookings, so nobody has to book again. */
  const move = async (r) => {
    const at = new Date(r.slot.startsAt)
    const res = await ask({
      title: 'Move this session',
      body: 'Bookings come with it, so nobody has to book again. Everyone is told the new time.',
      fields: [
        { name: 'date', label: 'New date', type: 'date', required: true,
          value: at.toISOString().slice(0, 10) },
        { name: 'time', label: 'New time', type: 'time', required: true,
          value: at.toTimeString().slice(0, 5) }
      ],
      confirmLabel: 'Move it'
    })
    if (!res) return
    try {
      await api.post(`/mentor/slots/${r.slot.id}/reschedule`,
        { startsAt: new Date(`${res.date}T${res.time}`).toISOString() })
      toast.push('Moved. Everyone booked has been told.')
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  return (
    <Page title="Slots and sessions"
      lede="Slots you have opened, and what has been booked into them.">
      <Card
        title="Your meeting room"
        note="One standing room, always open. Sessions point at it, so the link lives here and nowhere else. Learners never see it until they press join inside the window."
      >
        <div className="row g-2 align-items-end">
          <div className="col-md-6">
            <label className="form-label">Zoom link</label>
            <input className="form-control" value={room.joinUrl}
              onChange={(e) => setRoom((r) => ({ ...r, joinUrl: e.target.value }))} />
          </div>
          <div className="col-md-3">
            <label className="form-label">Passcode</label>
            <input className="form-control" value={room.passcode}
              onChange={(e) => setRoom((r) => ({ ...r, passcode: e.target.value }))} />
          </div>
          <div className="col-md-3">
            <button className="btn btn-pib w-100" onClick={saveRoom} disabled={!room.joinUrl}>Save room</button>
          </div>
        </div>
      </Card>

      <Card
        title="Publish a recording"
        note="Upload to YouTube as unlisted, then paste the id. The id is stored once and never shown again, not even here."
      >
        <div className="row g-2 align-items-end">
          <div className="col-md-4">
            <label className="form-label">Title</label>
            <input className="form-control" value={rec.title}
              onChange={(e) => setRec((r) => ({ ...r, title: e.target.value }))} />
          </div>
          <div className="col-md-3">
            <label className="form-label">Recording link</label>
            <input className="form-control" value={rec.externalId}
              onChange={(e) => setRec((r) => ({ ...r, externalId: e.target.value }))} />
          </div>
          <div className="col-md-3">
            <label className="form-label">Session</label>
            <select className="form-select" value={rec.slotId}
              onChange={(e) => setRec((r) => ({ ...r, slotId: e.target.value }))}>
              <option value="">Not linked</option>
              {rows.map((x) => (
                <option key={x.slot.id} value={x.slot.id}>{x.slot.kind} {fmtDateTime(x.slot.startsAt)}</option>
              ))}
            </select>
          </div>
          <div className="col-md-2">
            <button className="btn btn-pib w-100" onClick={publish}
              disabled={!rec.title || !rec.externalId}>Publish</button>
          </div>
          <div className="col-12">
            <label className="form-label">What it covers</label>
            <input className="form-control" value={rec.notes}
              onChange={(e) => setRec((r) => ({ ...r, notes: e.target.value }))} />
          </div>
        </div>
      </Card>

      <Card title="Release a slot" note="One to one slots go to premium learners. Group sessions go to a batch.">
        <div className="row g-2 align-items-end">
          <div className="col-md-3">
            <label className="form-label">Kind</label>
            <select className="form-select" value={draft.kind} onChange={set('kind')}>
              <option value="ONBOARDING">Onboarding call</option>
              <option value="INDUCTION">Induction</option>
              <option value="DOUBT">One to one doubt clearing</option>
              <option value="GROUP_DOUBT">Group doubt clearing</option>
              <option value="LIVE">Live session</option>
              <option value="PROJECT">Project session</option>
              <option value="MOCK">Mock interview</option>
            </select>
          </div>
          <div className="col-md-3">
            <label className="form-label">Starts at</label>
            <input className="form-control" type="datetime-local" value={draft.startsAt} onChange={set('startsAt')} />
          </div>
          <div className="col-md-2">
            <label className="form-label">Minutes</label>
            <input className="form-control" type="number" value={draft.durationMin} onChange={set('durationMin')} />
          </div>
          <div className="col-md-2">
            <label className="form-label">Seats</label>
            <input className="form-control" type="number" value={draft.capacity} onChange={set('capacity')} />
          </div>
          <div className="col-md-2">
            <label className="form-label">For</label>
            <select className="form-select" value={draft.trackScope} onChange={set('trackScope')}>
              <option value="PREMIUM">Premium</option>
              <option value="BATCH">Batch</option>
              <option value="BOTH">Both</option>
            </select>
          </div>
          <div className="col-md-10">
            <div className="small muted">
              Sessions use your standing room above. There is no per session link to paste.
            </div>
          </div>
          <div className="col-md-2">
            <button className="btn btn-pib w-100" onClick={release} disabled={!draft.startsAt}>Release</button>
          </div>
        </div>
      </Card>

      <Card
        title="Can people actually join?"
        actions={
          check && check !== 'running'
            ? <button className="btn btn-quiet" onClick={() => setCheck(null)}>Close</button>
            : <button className="btn btn-quiet" onClick={runCheck}>Check</button>
        }
      >
        {!check ? (
          <p className="text-muted small mb-0">
            Walks every upcoming session and reports what would happen if somebody pressed
            join. A link set after a session was scheduled is the usual reason one fails.
          </p>
        ) : check === 'running' ? (
          <p className="text-muted small mb-0">Checking.</p>
        ) : check.length === 0 ? (
          <Empty title="No upcoming sessions" />
        ) : (
          <table className="table table-pib mb-0">
            <thead>
              <tr><th>When</th><th>Kind</th><th>Runs it</th><th>Room</th><th>State</th></tr>
            </thead>
            <tbody>
              {check.map((r) => (
                <tr key={r.id}>
                  <td>{fmtDateTime(r.startsAt)}</td>
                  <td>{r.kind.replace('_', ' ').toLowerCase()}</td>
                  <td>{r.mentor || <span className="text-muted">Nobody</span>}</td>
                  <td>{r.room || <span className="text-muted">None</span>}</td>
                  <td>
                    {r.problem
                      ? <span><Tag kind="stop">Blocked</Tag> <span className="small">{r.problem}</span></span>
                      : r.window.open
                        ? <Tag kind="ok">Open now</Tag>
                        : <span>
                            <Tag kind="wait">Ready</Tag>{' '}
                            <span className="small text-muted">
                              opens {r.window.openBeforeMin} min before
                            </span>
                          </span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Card title="Your slots">
        {rows.length === 0 ? <Empty title="No slots released yet" /> : (
          <table className="table table-pib mb-0">
            <thead><tr><th>When</th><th>Kind</th><th>For</th><th>Seats</th><th>Booked by</th>
              <th /><th /></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.slot.id}>
                  <td className="mono">{fmtDateTime(r.slot.startsAt)}</td>
                  <td>{r.slot.kind}</td>
                  <td>{r.slot.trackScope}</td>
                  <td className="mono">{r.booked.length}/{r.slot.capacity}</td>
                  <td>{r.booked.filter(Boolean).join(', ') || '\u2014'}</td>
                  <td>{r.slot.recordingId ? <span className="tag tag-ok">Published</span> : <span className="small muted">Not yet</span>}</td>
                  <td className="text-end">
                    {r.slot.cancelled ? (
                      <Tag kind="stop">Cancelled</Tag>
                    ) : new Date(r.slot.startsAt) > new Date() ? (
                      <>
                        <button className="btn btn-quiet" onClick={() => move(r)}>Move</button>
                        <button className="btn btn-quiet ms-2" onClick={() => cancel(r)}>Cancel</button>
                      </>
                    ) : null}
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
