import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api/client'
import { Page, Card, Grid, Stat, Tag, LoadError, fmtDate } from '../../components/Ui'
import { TableSkeleton } from '../../components/Skeletons'
import Drawer from '../../components/Drawer'
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
  const [weekStart, setWeekStart] = useState(mondayOf(new Date()))
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [mentors, setMentors] = useState([])
  const [batches, setBatches] = useState([])
  const [adding, setAdding] = useState(null)
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
      const d = new Date(s.startsAt)
      const i = (d.getDay() + 6) % 7
      out[i].push(s)
    }
    return out
  }, [data])

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
            <Stat value={data.draft} label="in draft" tone={data.draft ? 'var(--warn)' : undefined} icon="edit" />
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
        <AddSession
          weekStart={weekStart}
          dayIndex={adding.dayIndex}
          mentors={mentors}
          batches={batches}
          isAdmin={data?.isAdmin}
          onClose={() => setAdding(null)}
          onSaved={() => { setAdding(null); load() }}
        />
      )}
    </Page>
  )
}

function AddSession({ weekStart, dayIndex, mentors, batches, isAdmin, onClose, onSaved }) {
  const toast = useToast()
  const [kind, setKind] = useState('GROUP_DOUBT')
  const [time, setTime] = useState('19:00')
  const [duration, setDuration] = useState(60)
  const [capacity, setCapacity] = useState(80)
  const [scope, setScope] = useState('BOTH')
  const [batchId, setBatchId] = useState('')
  const [joinUrl, setJoinUrl] = useState('')
  const [passcode, setPasscode] = useState('')
  const [allBatches, setAllBatches] = useState(true)
  const [hostId, setHostId] = useState('')
  const [topic, setTopic] = useState('')
  const [speaker, setSpeaker] = useState('')
  const [publish, setPublish] = useState(false)
  const [busy, setBusy] = useState(false)

  const date = useMemo(() => {
    const d = new Date(weekStart)
    d.setDate(d.getDate() + dayIndex)
    return d
  }, [weekStart, dayIndex])

  const save = async () => {
    setBusy(true)
    try {
      const [h, m] = time.split(':').map(Number)
      const at = new Date(date)
      at.setHours(h, m, 0, 0)
      await api.post('/week/sessions', {
        joinUrl: joinUrl.trim() || null,
        passcode: passcode.trim() || null,
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
      })
      toast.push(publish ? 'Session added and released.' : 'Session added as a draft.')
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
      title="New session"
      subtitle={`${DAYS[dayIndex]} · ${fmtDate(date)}`}
      onClose={onClose}
      footer={
        <>
          <button className="btn-quiet" onClick={onClose}>Cancel</button>
          <button className="btn" disabled={busy} onClick={save}>{busy ? 'Saving' : 'Add session'}</button>
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
            placeholder="Leave blank to use the host's own room"
            value={joinUrl}
            onChange={(e) => setJoinUrl(e.target.value)}
          />
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
