import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useLearner } from '../../context/LearnerContext'
import { useToast } from '../../context/ToastContext'
import { TableSkeleton } from '../../components/Skeletons'
import FaqBlock from '../../components/FaqBlock'
import { Card, Empty, Page, Tabs, fmtDateTime } from '../../components/Ui'
import Recordings from './Recordings'

const KIND_LABEL = {
  ONBOARDING: 'Onboarding call',
  INDUCTION: 'Induction',
  DOUBT: 'One to one doubt clearing',
  GROUP_DOUBT: 'Group doubt clearing',
  LIVE: 'Live session',
  PROJECT: 'Project session',
  MOCK: 'Mock interview'
}

export default function Sessions() {
  const { learner, loading, can, reload } = useLearner()
  const toast = useToast()
  const [slots, setSlots] = useState(null)
  const [calls, setCalls] = useState([])
  const [tab, setTab] = useState(0)

  const load = async () => {
    if (!learner) return
    setSlots(await api.get('/learner/slots'))
    /* the same test the panel below uses, so the page never asks for something the
       server will refuse */
    if (learner.trackType === 'PREMIUM' && can('biweekly_call')) {
      setCalls(await api.get('/learner/calls').catch(() => []))
    }
  }

  useEffect(() => { load() }, [learner])

  if (loading || !learner || !slots) return <TableSkeleton />

  /* the link is fetched here, on the click, inside the window: it is never in the list */
  const join = async (id) => {
    try {
      const r = await api.post(`/slots/${id}/join`)
      window.open(r.joinUrl, '_blank', 'noopener')
      if (r.passcode) toast.push(`Passcode ${r.passcode}`)
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const book = async (id) => {
    try {
      await api.post(`/learner/slots/${id}/book`)
      toast.push('Booked. Details are in your mail.')
      await load()
      await reload()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const premium = learner.trackType === 'PREMIUM'

  /*
   * Upcoming and recorded, on one page.
   *
   * They were two menu entries, and a learner looking for last Thursday's doubt clearing
   * was as likely to start under Sessions as under Recorded sessions. One is what is
   * coming, the other is what already happened; that is a tab, not a separate place.
   */
  return (
    <Page title="Sessions"
      lede="What is coming up, what you can book, and what has already been held.">
      <Tabs
        tabs={['Coming up', 'Recordings']}
        value={tab}
        onChange={setTab}
      />

      {tab === 1 ? <Recordings embedded /> : (
      <>
      {!premium && (
        <div className="locked-note mb-3">
          Doubt clearing runs as fixed group sessions for your batch. There is no
          one to one booking, so join the session that suits you.
        </div>
      )}

      <Card title="Open slots">
        {slots.length === 0 ? (
          <Empty title="Nothing open right now">
            New slots appear as your mentor releases them.
          </Empty>
        ) : (
          <table className="table table-pib mb-0">
            <thead>
              <tr><th>When</th><th>Session</th><th>Mentor</th><th>Seats</th><th /></tr>
            </thead>
            <tbody>
              {slots.map((s) => (
                <tr key={s.id}>
                  <td className="mono">{fmtDateTime(s.startsAt)}</td>
                  <td>{KIND_LABEL[s.kind] || s.kind}</td>
                  <td>{s.mentor}</td>
                  <td className="mono">{s.seatsLeft}</td>
                  <td className="text-end">
                    {s.open ? (
                      <button className="btn btn-pib" onClick={() => join(s.id)}>Join now</button>
                    ) : s.booked ? (
                      <span className="text-muted small">Booked, opens 10 min before</span>
                    ) : (premium || s.kind === 'GROUP_DOUBT') ? (
                      <button className="btn btn-quiet" onClick={() => book(s.id)}>Book</button>
                    ) : (
                      <span className="text-muted small">Opens 10 min before</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <FaqBlock placement="SESSIONS" track={premium ? 'PREMIUM' : 'BATCH'} />

      {premium && can('biweekly_call') && (
        <Card title="Progress calls" note="One to one, every two weeks with your mentor.">
          {calls.length === 0 ? (
            <Empty title="No calls logged yet">
              Your first call is scheduled once onboarding is done.
            </Empty>
          ) : (
            <table className="table table-pib mb-0">
              <thead><tr><th>Scheduled</th><th>Held</th><th>Notes</th><th>Next goal</th></tr></thead>
              <tbody>
                {calls.map((c) => (
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
        </Card>
      )}
      </>
      )}
    </Page>
  )
}
