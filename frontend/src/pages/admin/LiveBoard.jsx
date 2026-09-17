import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api/client'
import { Page, Card, Grid, Stat, Tag, LoadError, fmtDate } from '../../components/Ui'
import { TableSkeleton } from '../../components/Skeletons'
import Drawer from '../../components/Drawer'
import Icon from '../../components/Icon'
import { useDialog } from '../../components/Dialog'
import { useToast } from '../../context/ToastContext'

/**
 * The week, for everyone who runs a session.
 *
 * A learner belongs to one mentor. A live session does not. Recap, doubt clearing,
 * project and industry sessions rotate week by week, and this is the one place the whole
 * week is visible.
 *
 * It used to be admin only, which put the org's cadence somewhere the people delivering
 * it could not open: a mentor had two half screens instead, one for a repeating session
 * and one for a slot, and neither showed the week those screens produced. So the person
 * taking Thursday's doubt clearing could not see that somebody else had already taken it.
 *
 * Now every mentor sees the same week. What changes by role is what you may do to a row,
 * not what you may see. The server decides that and sends `canEdit` per session, so the
 * screen never has to guess and a hidden button is never the only thing standing between
 * somebody and an action they should not have.
 *
 * Releasing the week stays with the office. Publishing is an editorial act, not a
 * scheduling one, and it is the moment learners are told.
 */

const KINDS = [
  ['GROUP_DOUBT', 'Doubt clearing'],
  ['RECAP', 'Recap'],
  ['PROJECT', 'Project session'],
  ['INDUSTRY', 'Industry session'],
  ['LIVE', 'Live session'],
  ['INTERACTIVE', 'Interactive session'],
  ['DEBATE', 'Debate session'],
  ['INDUCTION', 'Induction']
]

const AUDIENCE = [
  ['BOTH', 'Everyone'],
  ['PREMIUM', 'Premium only'],
  ['BATCH', 'Batch only']
]

const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

function kindLabel(k) {
  return KINDS.find(([v]) => v === k)?.[1] || k
}

function mondayOf(d) {
  const x = new Date(d)
  const day = (x.getDay() + 6) % 7
  x.setDate(x.getDate() - day)
  return x.toISOString().slice(0, 10)
}

function shiftWeek(iso, by) {
  const x = new Date(iso)
  x.setDate(x.getDate() + by * 7)
  return x.toISOString().slice(0, 10)
}

export default function LiveBoard() {
  const toast = useToast()
  const { ask } = useDialog()
  const [weekStart, setWeekStart] = useState(mondayOf(new Date()))
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [mentors, setMentors] = useState([])
  const [batches, setBatches] = useState([])
  const [adding, setAdding] = useState(null)
  const [editing, setEditing] = useState(null)
  const [draftsOnly, setDraftsOnly] = useState(false)
  const [busy, setBusy] = useState(false)

  const load = () => {
    setData(null)
    api.get(`/week?weekStart=${weekStart}`).then((r) => {
      setData(r)
      setMentors(r.mentors || [])
    }).catch(setError)
  }

  useEffect(load, [weekStart])

  /* a mentor cannot read the admin batch list, and does not need it: the batch picker
     only appears when the viewer can scope a session to one */
  useEffect(() => {
    api.get('/admin/batches').then(setBatches).catch(() => setBatches([]))
  }, [])

  const byDay = useMemo(() => {
    const out = DAYS.map(() => [])
    if (!data) return out
    for (const s of data.sessions) {
      if (draftsOnly && s.published) continue
      const d = new Date(s.startsAt)
      const i = (d.getDay() + 6) % 7
      out[i].push(s)
    }
    return out
  }, [data, draftsOnly])

  const publish = async (on) => {
    setBusy(true)
    try {
      const r = await api.post('/week/publish', { weekStart, published: on })
      toast.push(on
        ? `Released ${r.changed} session${r.changed === 1 ? '' : 's'}. ${r.told} learners told.`
        : 'Week pulled back to draft.')
      load()
    } catch (e) {
      toast.push(e.message, 'bad')
    } finally {
      setBusy(false)
    }
  }

  /**
   * Taking a session off the board.
   *
   * Nobody booked it and it was a mistake, so it goes. Somebody booked it and it is a
   * cancellation, which stays on their week with a reason on it rather than vanishing.
   */
  const drop = async (s) => {
    const ok = await ask({
      title: 'Take this session off?',
      body: `${kindLabel(s.kind)} at ${String(s.time).slice(0, 5)}. If anyone has booked it, `
          + 'it is marked cancelled and they are told, rather than disappearing from their week.',
      fields: [{ name: 'reason', label: 'Reason', placeholder: 'Shown to anyone who booked it' }],
      intent: 'danger',
      confirmLabel: 'Take it off'
    })
    if (!ok) return
    try {
      const r = await api.del(`/week/sessions/${s.id}`
        + (ok.reason ? `?reason=${encodeURIComponent(ok.reason)}` : ''))
      toast.push(r.deleted ? 'Session deleted.' : `Cancelled. ${r.told} told.`)
      load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const setHost = async (slotId, hostId) => {
    try {
      await api.post(`/week/sessions/${slotId}/host`, { hostId })
      toast.push('Host changed for this week only.')
      load()
    } catch (e) {
      toast.push(e.message, 'bad')
    }
  }

  if (error) return <LoadError error={error} onRetry={load} />

  return (
    <Page
      title="Weekly sessions"
      lede="The week as a whole. Sessions rotate across mentors; learners stay with theirs."
      actions={
        <>
          <button className="btn-quiet" onClick={() => setWeekStart(shiftWeek(weekStart, -1))}>Previous</button>
          <button className="btn-quiet" onClick={() => setWeekStart(mondayOf(new Date()))}>This week</button>
          <button className="btn-quiet" onClick={() => setWeekStart(shiftWeek(weekStart, 1))}>Next</button>
          {data?.canPublish && (
            <button className="btn" disabled={busy} onClick={() => publish(true)}>Release week</button>
          )}
        </>
      }
    >
      {!data ? <TableSkeleton /> : (
        <>
          <Grid cols={4}>
            <Stat value={data.sessions.length} label="sessions this week" icon="sessions" />
            <Stat value={data.published} label="released" tone="var(--teal-600)" icon="check" />
            {/* the pencil on this tile was decoration and people pressed it for a week.
                it filters the board now, which is what pressing it looked like it did */}
            <Stat
              value={data.draft} label="in draft" icon="edit"
              tone={data.draft ? 'var(--warn)' : undefined}
              active={draftsOnly}
              onClick={() => setDraftsOnly((v) => !v)}
              hint={draftsOnly ? 'Showing drafts only. Press again for the whole week.' : 'Show only these'}
            />
            <Stat
              value={new Set(data.sessions.map((s) => s.hostId).filter(Boolean)).size}
              label="mentors hosting" icon="people"
            />
          </Grid>

          <div className="board-week">
            {DAYS.map((d, i) => (
              <div className={`board-day ${byDay[i].length ? '' : 'is-empty'}`} key={d}>
                <div className="board-day-head">
                  <span className="board-day-name">{d}</span>
                  {data.canCreate && (
                    <button
                      className="board-add"
                      title={`Add a session on ${d}`}
                      onClick={() => setAdding({ dayIndex: i })}
                    >+</button>
                  )}
                </div>

                {byDay[i].length === 0 && <p className="board-none">Nothing scheduled</p>}

                {byDay[i].map((s) => (
                  <article
                    className={`board-card k-${s.kind.toLowerCase()} ${s.published ? '' : 'is-draft'}`
                      + (s.mine ? ' is-mine' : '')}
                    key={s.id}
                  >
                    <header>
                      <span className="board-time mono">{String(s.time).slice(0, 5)}</span>
                      {!s.published && <Tag kind="wait">Draft</Tag>}
                      {s.cancelled && <Tag kind="stop">Cancelled</Tag>}
                      {s.canEdit && !s.cancelled && (
                        <span className="board-acts">
                          <button className="board-act" title="Edit this session"
                            onClick={() => setEditing(s)}>
                            <Icon name="edit" size={13} />
                          </button>
                          <button className="board-act" title="Take it off"
                            onClick={() => drop(s)}>
                            <Icon name="close" size={13} />
                          </button>
                        </span>
                      )}
                    </header>
                    <p className="board-kind">{kindLabel(s.kind)}</p>
                    {s.topic && <p className="board-topic">{s.topic}</p>}
                    {s.speaker && <p className="board-speaker">{s.speaker}</p>}

                    {/* only the office moves a session to another host, so a mentor
                        sees who has it rather than a control that would be refused */}
                    {data.canReassign ? (
                      <label className="board-host">
                        <span className="board-host-label">Host</span>
                        <select value={s.hostId || ''} onChange={(e) => setHost(s.id, e.target.value)}>
                          <option value="">Unassigned</option>
                          {mentors.map((m) => (
                            <option key={m.id} value={m.id}>{m.name}</option>
                          ))}
                        </select>
                      </label>
                    ) : (
                      <p className="board-host-plain">
                        {s.mine ? <Tag kind="ok">Yours</Tag> : (s.host || 'Unassigned')}
                      </p>
                    )}

                    <footer>
                      <span className="board-aud">
                        {AUDIENCE.find(([v]) => v === s.trackScope)?.[1] || s.trackScope}
                        {s.openToAllBatches && s.trackScope !== 'PREMIUM' ? ' · all batches' : ''}
                      </span>
                      <span className="mono board-seats">{s.booked}/{s.capacity}</span>
                    </footer>
                  </article>
                ))}
              </div>
            ))}
          </div>

          <Card
            title="Why this exists"
            note="The rule the product used to get wrong"
            className="board-note"
          >
            <p className="muted">
              A premium learner's one to one stays with their own mentor and always will.
              Everything on this board is open to whoever is hosting that week, and a
              doubt clearing session is joinable by any batch unless it is marked private
              to one. Releasing the week is what makes it visible to learners.
            </p>
            {!data.canCreate && (
              <p className="muted">
                Sessions here are set up by the office. You can see the whole week and the
                ones marked yours are the ones you are taking.
              </p>
            )}
            {data.canCreate && !data.isAdmin && (
              <p className="muted">
                You can add sessions and they go on as yours. Moving one to another host,
                and releasing the week to learners, stay with the office.
              </p>
            )}
          </Card>
        </>
      )}

      {adding && (
        <SessionForm
          weekStart={weekStart}
          dayIndex={adding.dayIndex}
          mentors={mentors}
          batches={batches}
          isAdmin={data?.isAdmin}
          onClose={() => setAdding(null)}
          onSaved={() => { setAdding(null); load() }}
        />
      )}

      {editing && (
        <SessionForm
          existing={editing}
          weekStart={weekStart}
          dayIndex={(new Date(editing.startsAt).getDay() + 6) % 7}
          mentors={mentors}
          batches={batches}
          isAdmin={data?.isAdmin}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); load() }}
        />
      )}
    </Page>
  )
}

/**
 * One form, for a new session and for one that already exists.
 *
 * Adding was the only thing the board could do. Everything about a session was fixed
 * the moment it was created, so a wrong time or a link to the wrong meeting could only
 * be worked around by putting a second session next to the first. The same drawer now
 * opens on an existing row with its values in it.
 */
function SessionForm({ existing, weekStart, dayIndex, mentors, batches, isAdmin, onClose, onSaved }) {
  const toast = useToast()
  const at0 = existing ? new Date(existing.startsAt) : null
  const [kind, setKind] = useState(existing?.kind || 'GROUP_DOUBT')
  const [time, setTime] = useState(at0
    ? `${String(at0.getHours()).padStart(2, '0')}:${String(at0.getMinutes()).padStart(2, '0')}`
    : '19:00')
  const [duration, setDuration] = useState(existing?.durationMin || 60)
  const [capacity, setCapacity] = useState(existing?.capacity || 80)
  const [scope, setScope] = useState(existing?.trackScope || 'BOTH')
  const [batchId, setBatchId] = useState(existing?.batchId || '')
  const [joinUrl, setJoinUrl] = useState('')
  const [passcode, setPasscode] = useState('')
  const [allBatches, setAllBatches] = useState(
    existing ? !!existing.openToAllBatches : true)
  const [hostId, setHostId] = useState(existing?.hostId || '')
  const [topic, setTopic] = useState(existing?.topic || '')
  const [speaker, setSpeaker] = useState(existing?.speaker || '')
  const [publish, setPublish] = useState(existing ? !!existing.published : false)
  const [busy, setBusy] = useState(false)

  const date = useMemo(() => {
    if (existing) return new Date(existing.startsAt)
    const d = new Date(weekStart)
    d.setDate(d.getDate() + dayIndex)
    return d
  }, [weekStart, dayIndex, existing])

  /*
   * A link is checked here as well as on the server. The box used to take three letters
   * or a sentence, store it, and fail at the moment somebody tried to join.
   */
  const linkProblem = joinUrl.trim() && !/^https?:\/\/\S+\.\S+/i.test(joinUrl.trim())
    ? 'A join link starts with https:// and points at a real address.'
    : null

  const save = async () => {
    if (linkProblem) { toast.push(linkProblem, 'bad'); return }
    setBusy(true)
    try {
      const [h, m] = time.split(':').map(Number)
      const at = new Date(date)
      at.setHours(h, m, 0, 0)
      const payload = {
        kind,
        startsAt: at.toISOString(),
        durationMin: Number(duration),
        capacity: Number(capacity),
        trackScope: scope,
        batchId: batchId || null,
        openToAllBatches: allBatches,
        hostId: hostId || null,
        topic: topic || null,
        speaker: speaker || null,
        published: publish
      }
      /* an empty link on an edit means "leave the room alone", not "clear it" */
      if (joinUrl.trim() || !existing) {
        payload.joinUrl = joinUrl.trim() || null
        payload.passcode = passcode.trim() || null
      }
      if (existing) {
        await api.put(`/week/sessions/${existing.id}`, payload)
        toast.push('Session updated.')
      } else {
        await api.post('/week/sessions', payload)
        toast.push(publish ? 'Session added and released.' : 'Session added as a draft.')
      }
      onSaved()
    } catch (e) {
      toast.push(e.message, 'bad')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Drawer
      open
      wide
      title={existing ? 'Edit session' : 'New session'}
      subtitle={`${DAYS[dayIndex]} · ${fmtDate(date)}`}
      onClose={onClose}
      footer={
        <>
          <button className="btn-quiet" onClick={onClose}>Cancel</button>
          <button className="btn" disabled={busy} onClick={save}>
            {busy ? 'Saving' : existing ? 'Save changes' : 'Add session'}
          </button>
        </>
      }
    >
      <div className="form-grid form-grid-2">
        <p className="form-section">What it is</p>
        <label>
          <span>Kind</span>
          <select value={kind} onChange={(e) => setKind(e.target.value)}>
            {KINDS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </select>
        </label>

        <p className="form-section">When</p>
        <label>
          <span>Start</span>
          <input type="time" value={time} onChange={(e) => setTime(e.target.value)} />
        </label>

        <label>
          <span>Minutes</span>
          <input type="number" min="15" step="15" value={duration} onChange={(e) => setDuration(e.target.value)} />
        </label>

        <label>
          <span>Seats</span>
          <input type="number" min="1" value={capacity} onChange={(e) => setCapacity(e.target.value)} />
        </label>

        <p className="form-section">Who it is for</p>
        <label>
          <span>Audience</span>
          <select value={scope} onChange={(e) => setScope(e.target.value)}>
            {AUDIENCE.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </select>
        </label>

        {/* a mentor's session goes on as theirs; the server enforces that either way */}
        {isAdmin && (
          <label>
            <span>Host this week</span>
            <select value={hostId} onChange={(e) => setHostId(e.target.value)}>
              <option value="">Decide later</option>
              {mentors.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
            </select>
          </label>
        )}

        {scope !== 'PREMIUM' && (
          <>
            <label>
              <span>Tie to one batch</span>
              <select value={batchId} onChange={(e) => setBatchId(e.target.value)}>
                <option value="">No, org wide</option>
                {batches.map((b) => <option key={b.id} value={b.id}>{b.code || b.name}</option>)}
              </select>
            </label>

            <label className="check">
              <input type="checkbox" checked={allBatches} onChange={(e) => setAllBatches(e.target.checked)} />
              <span>Open to learners from any batch</span>
            </label>
          </>
        )}

        {/*
          * The link.
          *
          * A session used to silently inherit the host's standing room and there was no
          * way to give one its own, so an industry session on a guest's Zoom had nowhere
          * to go. Blank still means "use the host's room", which is what it always did.
          */}
        <p className="form-section">Where it happens</p>
        <label className="wide">
          <span>Join link</span>
          <input
            type="url"
            className={linkProblem ? 'is-invalid' : ''}
            placeholder={existing
              ? 'Leave blank to keep the link it has'
              : "Leave blank to use the host's own room"}
            value={joinUrl}
            onChange={(e) => setJoinUrl(e.target.value)}
          />
          {linkProblem && <small className="dlg-problem">{linkProblem}</small>}
        </label>
        <label>
          <span>Passcode</span>
          <input value={passcode} onChange={(e) => setPasscode(e.target.value)}
            placeholder="Only if the link needs one" />
        </label>

        <label className="wide">
          <span>Topic</span>
          <input
            value={topic} placeholder="What this session covers"
            onChange={(e) => setTopic(e.target.value)}
          />
        </label>

        {kind === 'INDUSTRY' && (
          <label className="wide">
            <span>Speaker</span>
            <input
              value={speaker} placeholder="Name, role, company"
              onChange={(e) => setSpeaker(e.target.value)}
            />
          </label>
        )}

        {isAdmin && (
          <label className="check wide">
            <input type="checkbox" checked={publish} onChange={(e) => setPublish(e.target.checked)} />
            <span>Release to learners now, rather than holding it in the week's draft</span>
          </label>
        )}
        {!isAdmin && (
          <p className="muted wide">
            This is added to the week as a draft. The office releases the week to learners.
          </p>
        )}
      </div>
    </Drawer>
  )
}
