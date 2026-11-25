import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { useAuth } from '../../context/AuthContext'
import { TableSkeleton } from '../../components/Skeletons'
import { useDialog } from '../../components/Dialog'
import FileUpload from '../../components/FileUpload'
import HeldDialog from '../../components/HeldDialog'
import { Card, Empty, Page, Tag, fmtDateTime } from '../../components/Ui'

export default function MentorSlots() {
  const toast = useToast()
  const { user } = useAuth()
  /* the join check lists every room and who can get in, so the server keeps it to the office */
  const office = user?.role === 'ADMIN' || user?.role === 'SUPER_ADMIN'
  const { ask } = useDialog()
  const [rows, setRows] = useState(null)
  const [draft, setDraft] = useState({
    kind: 'ONBOARDING', startsAt: '', durationMin: 30, capacity: 1, trackScope: 'PREMIUM', joinUrl: ''
  })

  const [room, setRoom] = useState({ joinUrl: '', passcode: '' })
  const [rec, setRec] = useState({ title: '', videoLink: '', slotId: '', notes: '', batchId: '' })
  const [recFile, setRecFile] = useState(null)
  /* the batches this mentor teaches, for sharing a recording with one of them, and the
     upload ceiling the server will actually apply, so the box can say the real number */
  const [batches, setBatches] = useState([])
  const [limits, setLimits] = useState(null)
  const [check, setCheck] = useState(null)
  /* publish and release each post once; a double press made two of them */
  const [busy, setBusy] = useState(null)
  /* the slot whose "mark as held" dialog is open; above the early return so the hook
     count is the same on every render */
  const [holding, setHolding] = useState(null)

  const load = async () => {
    setRows(await api.get('/mentor/slots'))
    const r = await api.get('/mentor/room')
    if (r.set) setRoom({ joinUrl: r.joinUrl, passcode: r.passcode })
    api.get('/mentor/batches').then(setBatches).catch(() => setBatches([]))
    api.get('/files/limits').then(setLimits).catch(() => setLimits(null))
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

  /**
   * Publishing a recording from this page.
   *
   * This posted a field called `externalId`, which is not something the publish endpoint
   * has ever read. Jackson dropped it, the server saw neither a link nor a file and
   * refused, and the form said the link was wrong. The box was labelled "Recording link"
   * and its note asked for a YouTube id, so whichever of the two you pasted was the
   * wrong one. It takes the link now, or the file itself.
   */
  const recLinkProblem = rec.videoLink.trim()
    && !/^https?:\/\/\S+\.\S+/i.test(rec.videoLink.trim())
    && !/^[\w-]{8,}$/.test(rec.videoLink.trim())
    ? 'Paste the full YouTube or Google Drive link.'
    : null

  /* what the pasted link is, said back before anything is sent, because a Drive folder
     and a YouTube video are shared and watched differently */
  const recLinkKind = /drive\.google\.com\/(?:drive\/)?(?:u\/\d+\/)?folders\//i.test(rec.videoLink)
    ? 'A Google Drive folder. Learners open it in Drive.'
    : /drive\.google\.com|docs\.google\.com\/file/i.test(rec.videoLink)
      ? 'A Google Drive video. It plays in Drive’s frame, so share the file with the batch.'
      : /youtu\.?be/i.test(rec.videoLink) ? 'A YouTube video.' : null

  /*
   * Sharing with a batch. Without a batch this posts exactly what it always did. With
   * one, the recording goes out as that batch's induction recording: the kind the
   * library files under Induction and the audience rule that keeps it to that batch.
   * The server checks the mentor teaches it, so the list here is only a convenience.
   */
  const publish = async () => {
    if (recLinkProblem) { toast.push(recLinkProblem, 'bad'); return }
    if (busy) return
    setBusy('publish')
    try {
      await api.post('/mentor/recordings', {
        title: rec.title,
        slotId: rec.slotId || null,
        videoLink: rec.videoLink.trim() || null,
        fileId: recFile?.id || null,
        coveredSummary: rec.notes || null,
        ...(rec.batchId ? { kind: 'INDUCTION', audience: 'BATCH', batchId: rec.batchId } : {})
      })
      toast.push(rec.batchId
        ? 'Shared. Everyone in that batch can watch it now under Recordings.'
        : 'Recording published to the learners who were in that session.')
      setRec({ title: '', videoLink: '', slotId: '', notes: '', batchId: '' })
      setRecFile(null)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
    finally { setBusy(null) }
  }

  const set = (k) => (e) => setDraft((d) => ({ ...d, [k]: e.target.value }))

  const slotLinkProblem = draft.joinUrl.trim()
    && !/^https?:\/\/\S+\.\S+/i.test(draft.joinUrl.trim())
    ? 'A join link starts with https:// and points at a real address.'
    : null

  const release = async () => {
    if (busy) return
    setBusy('release')
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
    finally { setBusy(null) }
  }

  /**
   * Cancelling tells everyone who booked, which is why it asks for a reason: the
   * reason is the message they get, and "cancelled" on its own helps nobody.
   */
  /* the link is fetched on the click, never listed: the same rule the learner side keeps */
  const join = async (r) => {
    try {
      const res = await api.post(`/slots/${r.slot.id}/join`)
      window.open(res.joinUrl, '_blank', 'noopener')
      if (res.passcode) toast.push(`Passcode ${res.passcode}`)
    } catch (e) { toast.push(e.message, 'bad') }
  }

  /*
   * Confirming the session ran. Nothing automatic can do this: the room is a pasted
   * link and there is no feed back from it. The host says who was there, in the same
   * dialog the desk uses, and that is what records attendance and, for an onboarding
   * call, clears the gate. (The dialog's state, `holding`, is declared at the top.)
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
        title="Publish or share a recording"
        note="Paste a YouTube link, a Google Drive video or folder link, or upload the file. Pick a batch to share an onboarding recording with everyone in it. The link is stored once and is never shown to a learner."
      >
        <div className="row g-2 align-items-end">
          <div className="col-md-4">
            <label className="form-label">Title</label>
            <input className="form-control" value={rec.title}
              onChange={(e) => setRec((r) => ({ ...r, title: e.target.value }))} />
          </div>
          <div className="col-md-4">
            <label className="form-label">Recording link</label>
            <input
              className={`form-control ${recLinkProblem ? 'is-invalid' : ''}`}
              placeholder="https://youtu.be/... or https://drive.google.com/..."
              value={rec.videoLink}
              disabled={!!recFile}
              onChange={(e) => setRec((r) => ({ ...r, videoLink: e.target.value }))}
            />
            {recLinkProblem && <div className="invalid-feedback d-block">{recLinkProblem}</div>}
            {!recLinkProblem && recLinkKind && <div className="small muted mt-1">{recLinkKind}</div>}
          </div>
          <div className="col-md-4">
            <label className="form-label">Share with a batch</label>
            <select className="form-select" value={rec.batchId}
              onChange={(e) => setRec((r) => ({ ...r, batchId: e.target.value }))}>
              <option value="">Not shared with a batch</option>
              {batches.map((b) => (
                <option key={b.id} value={b.id}>{b.code || b.name}{b.name && b.code ? ` \u2014 ${b.name}` : ''}</option>
              ))}
            </select>
          </div>

          {/* the other half of the choice: a file, for a session nobody put on YouTube */}
          <div className="col-md-6">
            <label className="form-label">Or upload the recording file</label>
            <FileUpload
              purpose="RECORDING"
              multiple={false}
              label="Choose a video"
              accept={(limits?.videoExtensions || ['mp4', 'webm', 'mov', 'm4v', 'mkv']).map((x) => `.${x}`).join(',')}
              maxBytes={limits?.videoMaxBytes}
              hint="Drop it here, or click to choose. mp4, webm, mov, m4v or mkv."
              onUploaded={(f) => { setRecFile(f); if (!rec.title) setRec((r) => ({ ...r, title: f.filename })) }}
              onRemoved={(f) => setRecFile((cur) => (cur?.id === f.id ? null : cur))}
            />
            {recFile && (
              <button className="btn btn-quiet btn-sm mt-2" onClick={() => setRecFile(null)}>
                Clear the file
              </button>
            )}
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
          <div className="col-md-3">
            <button className="btn btn-pib w-100" onClick={publish}
              disabled={!rec.title || (!rec.videoLink.trim() && !recFile) || busy === 'publish'}>
              {busy === 'publish' ? (rec.batchId ? 'Sharing\u2026' : 'Publishing\u2026')
                : (rec.batchId ? 'Share with batch' : 'Publish')}
            </button>
          </div>

          <div className="col-md-12">
            <label className="form-label">Description</label>
            <input className="form-control" value={rec.notes}
              placeholder="What it covers. This is what makes it findable later."
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
          {/*
            * A link for this one session.
            *
            * The box used to be here, the value was posted, and it was thrown away: the
            * endpoint bound onto Slot, which has no link field on it, so a session was
            * saved with no room and the learner who booked it was told at the window
            * that no meeting link was set. Then the box was removed and the note said
            * there was no per session link, which made a guest's Zoom impossible to use
            * at all. It is back, and it is stored.
            */}
          <div className="col-md-5">
            <label className="form-label">Link for this session</label>
            <input
              className={`form-control ${slotLinkProblem ? 'is-invalid' : ''}`}
              placeholder={room.joinUrl ? 'Leave blank to use your standing room' : 'https://zoom.us/j/...'}
              value={draft.joinUrl}
              onChange={set('joinUrl')}
            />
            {slotLinkProblem && <div className="invalid-feedback d-block">{slotLinkProblem}</div>}
          </div>
          <div className="col-md-5">
            {!room.joinUrl && !draft.joinUrl.trim() && (
              <div className="small" style={{ color: 'var(--stop)' }}>
                You have no standing room saved above and no link here, so anyone who books
                this will reach the window and find nothing to join.
              </div>
            )}
          </div>
          <div className="col-md-2">
            <button className="btn btn-pib w-100" onClick={release}
              disabled={!draft.startsAt || !!slotLinkProblem || busy === 'release'}>
              {busy === 'release' ? 'Releasing\u2026' : 'Release'}
            </button>
          </div>
        </div>
      </Card>

      {office && (
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
      )}

      <Card title="Your slots">
        {rows.length === 0 ? <Empty title="No slots released yet" /> : (
          <table className="table table-pib mb-0">
            <thead><tr><th>When</th><th>Kind</th><th>For</th><th>Seats</th><th>Booked by</th>
              <th>State</th><th /><th /></tr></thead>
            <tbody>
              {rows.map((r) => {
                const future = new Date(r.slot.startsAt) > new Date()
                const live = !r.slot.cancelled && !r.slot.held
                return (
                <tr key={r.slot.id}>
                  <td className="mono">{fmtDateTime(r.slot.startsAt)}</td>
                  <td>{r.slot.kind}</td>
                  <td>{r.slot.trackScope}</td>
                  <td className="mono">{r.booked.length}/{r.slot.capacity}</td>
                  <td>{r.booked.filter(Boolean).join(', ') || '\u2014'}</td>
                  {/* the session's own state, separate from whether its recording is up */}
                  <td>
                    {r.slot.cancelled ? <Tag kind="stop">Cancelled</Tag>
                      : r.slot.held ? <Tag kind="ok">Held</Tag>
                      : future ? <Tag kind="wait">Upcoming</Tag>
                      : <Tag kind="wait">Not confirmed</Tag>}
                  </td>
                  <td>{r.slot.recordingId ? <span className="tag tag-ok">Published</span> : <span className="small muted">Not yet</span>}</td>
                  <td className="text-end">
                    {live && (
                      <button className="btn btn-quiet" onClick={() => join(r)}>Join</button>
                    )}
                    {live && !future && (
                      <button className="btn btn-quiet ms-2" onClick={() => setHolding(r)}>Mark as held</button>
                    )}
                    {live && future && (
                      <>
                        <button className="btn btn-quiet ms-2" onClick={() => move(r)}>Move</button>
                        <button className="btn btn-quiet ms-2" onClick={() => cancel(r)}>Cancel</button>
                      </>
                    )}
                  </td>
                </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </Card>

      {holding && (
        <HeldDialog
          slotId={holding.slot.id}
          kindLabel={holding.slot.kind}
          onClose={() => setHolding(null)}
          onHeld={load}
        />
      )}
    </Page>
  )
}
