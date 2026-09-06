import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import Avatar from '../../components/Avatar'
import Drawer from '../../components/Drawer'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, Page, Stat, Tag, fmtDate, fmtDateTime } from '../../components/Ui'

const STATES = [['PRESENT', 'Here'], ['LATE', 'Late'], ['ABSENT', 'Away']]

const KIND_LABEL = {
  GROUP_DOUBT: 'Group doubt clearing', DOUBT: 'Doubt clearing', PROJECT: 'Project session',
  LIVE: 'Live session', ONBOARDING: 'Onboarding call', INDUCTION: 'Induction',
  MOCK: 'Mock interview'
}

/**
 * Who turned up, and what was covered.
 *
 * Both have worked on the server since schedules were built and neither had a screen,
 * so a session left no trace beyond the fact it happened. Notes go on the session, which
 * means every attendee's record carries them without anyone copying anything.
 */
export default function Attendance() {
  const toast = useToast()
  const [slots, setSlots] = useState(null)
  const [open, setOpen] = useState(null)
  const [sheet, setSheet] = useState(null)
  const [notes, setNotes] = useState('')

  /* the endpoint returns { slot, booked } per row, not a flat slot: this screen
     read it as flat and would have thrown for every mentor */
  const load = () => api.get('/mentor/slots').then((rows) =>
    setSlots(rows
      .map((r) => ({ ...r.slot, bookedNames: r.booked || [] }))
      .filter(isPast)
      .sort((a, b) => new Date(b.startsAt) - new Date(a.startsAt))))
  useEffect(() => { load() }, [])

  const isPast = (s) =>
    !s.cancelled && new Date(s.startsAt).getTime() + (s.durationMin || 0) * 60000 < Date.now()

  const openSheet = async (s) => {
    setOpen(s)
    setSheet(null)
    try {
      const r = await api.get(`/mentor/slots/${s.id}/attendance`)
      setSheet(r)
      setNotes(r.notes || '')
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const mark = async (learnerId, state) => {
    /* marked one at a time and saved immediately: a mentor doing this from a phone
       after a session should never lose it to a missed save */
    setSheet((s) => ({
      ...s,
      rows: s.rows.map((r) => (r.learnerId === learnerId ? { ...r, state } : r))
    }))
    try {
      await api.post(`/mentor/slots/${open.id}/attendance`, { learnerId, state })
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const markRest = async (state) => {
    const rest = sheet.rows.filter((r) => !r.state)
    if (rest.length === 0) return
    const before = sheet
    setSheet((s) => ({
      ...s,
      rows: s.rows.map((r) => (r.state ? r : { ...r, state }))
    }))
    toast.pushUndo({
      message: `${rest.length} marked away.`,
      commit: async () => {
        for (const r of rest) {
          await api.post(`/mentor/slots/${open.id}/attendance`,
            { learnerId: r.learnerId, state })
        }
      },
      revert: () => setSheet(before)
    })
  }

  const saveNotes = async () => {
    try {
      await api.post(`/mentor/slots/${open.id}/notes`, { notes })
      toast.push('Saved. Everyone who attended sees this on their record.')
    } catch (e) { toast.push(e.message, 'bad') }
  }

  if (!slots) return <TableSkeleton />

  /* the slot itself does not say whether it was marked, so this counts what has
     no recording of attendance rather than inventing a flag */
  const unmarked = slots.length

  return (
    <Page title="Attendance and notes"
      lede="Who turned up, and what you noted afterwards.">
      <div className="row g-3 mb-4">
        <div className="col-6 col-lg-4"><Stat value={slots.length} label="Sessions held" icon="sessions" /></div>
        <div className="col-6 col-lg-4">
          <Stat value={unmarked} label="Not marked yet" icon="queues"
            tone={unmarked ? 'var(--warn)' : undefined} />
        </div>
        <div className="col-12 col-lg-4"><Stat value={10} label="Points per session" icon="roadmap" /></div>
      </div>

      <Card note="Attendance counts towards a learner's standing, so a session nobody marks is a gap in everyone's score.">
        {slots.length === 0 ? (
          <Empty title="No sessions have finished yet" icon="sessions" />
        ) : (
          <table className="table table-pib stacked mb-0">
            <thead><tr><th>Session</th><th>Held</th><th>Booked</th><th /></tr></thead>
            <tbody>
              {slots.map((s) => (
                <tr key={s.id}>
                  <td data-label="Session">
                    <strong>{KIND_LABEL[s.kind] || s.kind}</strong>
                    {s.batchId && <div className="small muted">{s.batchId}</div>}
                  </td>
                  <td data-label="Held" className="mono">{fmtDateTime(s.startsAt)}</td>
                  <td data-label="Booked" className="mono">
                    {s.bookedNames.length || '\u2014'}
                  </td>
                  <td className="text-end">
                    <button className="btn btn-quiet" onClick={() => openSheet(s)}>
                      Open sheet
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Drawer
        open={Boolean(open)}
        title="Attendance"
        subtitle={open ? fmtDate(open.startsAt) : ''}
        onClose={() => setOpen(null)}
      >
        {sheet && (
          <>
            <div className="d-flex gap-2 mb-3">
              <Tag kind="ok">{sheet.present} here</Tag>
              <span className="small muted">
                {sheet.rows.filter((r) => !r.state).length} still to mark
              </span>
              <button className="btn btn-quiet ms-auto" onClick={() => markRest('ABSENT')}>
                Mark the rest away
              </button>
            </div>

            <table className="table table-pib mb-4">
              <tbody>
                {sheet.rows.map((r) => (
                  <tr key={r.learnerId}>
                    <td>
                      <span className="who-cell"><Avatar name={r.name} size={30} />{r.name}</span>
                    </td>
                    <td className="text-end">
                      <div className="att-set">
                        {STATES.map(([v, l]) => (
                          <button
                            key={v}
                            className={`att ${r.state === v ? 'on ' + v.toLowerCase() : ''}`}
                            onClick={() => mark(r.learnerId, v)}
                          >
                            {l}
                          </button>
                        ))}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            <Card title="What was covered"
              note="Goes on the session, so every attendee's record carries it, and the recording inherits it when it is published.">
              <textarea
                className="form-control"
                rows={4}
                value={notes}
                onChange={(e) => setNotes(e.target.value)}
                placeholder="The questions that came up, and anything to follow up."
              />
              <button className="btn btn-pib mt-3" onClick={saveNotes}>Save notes</button>
            </Card>
          </>
        )}
      </Drawer>
    </Page>
  )
}
