import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { useToast } from '../context/ToastContext'
import Drawer from './Drawer'

/**
 * The host confirms a session ran, and says who was in it.
 *
 * Everyone who booked is listed by name and marked present unless unticked. Below that,
 * the learners the host may record as having come without booking: the one to one call
 * that happened although the slot was never booked (it was a draft the learner could not
 * see, or they were simply sent the link) used to be marked held with nobody in it, and
 * the learner's onboarding stayed open. Naming them here gives them the booking and the
 * attendance they would have had, through the same server path, so the gate clears.
 */
export default function HeldDialog({ slotId, kindLabel, onClose, onHeld }) {
  const toast = useToast()
  const [opts, setOpts] = useState(null)
  const [error, setError] = useState(null)
  const [absent, setAbsent] = useState({})
  const [extra, setExtra] = useState({})
  const [q, setQ] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    setOpts(null); setError(null); setAbsent({}); setExtra({}); setQ('')
    if (!slotId) return
    api.get(`/mentor/slots/${slotId}/held-options`).then(setOpts).catch((e) => setError(e.message))
  }, [slotId])

  if (!slotId) return null

  const booked = opts?.booked || []
  const needle = q.trim().toLowerCase()
  const candidates = (opts?.candidates || []).filter((c) =>
    !needle || c.name.toLowerCase().includes(needle))
  const present = booked.filter((b) => !absent[b.learnerId]).length
  const added = Object.keys(extra).filter((id) => extra[id]).length
  const nobody = booked.length === 0 && added === 0

  const confirm = async () => {
    setBusy(true)
    try {
      await api.post(`/mentor/slots/${slotId}/held`, {
        absentLearnerIds: booked.filter((b) => absent[b.learnerId]).map((b) => b.learnerId),
        attendedLearnerIds: Object.keys(extra).filter((id) => extra[id])
      })
      toast.push(nobody ? 'Marked as held, with nobody present.'
        : `Marked as held. ${present + added} present.`)
      onHeld?.()
      onClose()
    } catch (e) { toast.push(e.message, 'bad') } finally { setBusy(false) }
  }

  return (
    <Drawer
      open
      title="Mark this session as held"
      subtitle={kindLabel}
      onClose={onClose}
      footer={(
        <div className="d-flex gap-2 align-items-center">
          <span className="tiny muted me-auto">
            {nobody ? 'Nobody is marked present.' : `${present + added} marked present.`}
          </span>
          <button className="btn" onClick={onClose} disabled={busy}>Not yet</button>
          <button className="btn btn-pib" onClick={confirm} disabled={busy || !opts}>
            {busy ? 'Saving' : 'Mark as held'}
          </button>
        </div>
      )}
    >
      {error && <p className="text-danger small">{error}</p>}
      {!opts && !error && <p className="small muted">Loading who booked&hellip;</p>}
      {opts && (
        <div className="d-grid gap-3">
          <p className="small muted mb-0">
            Only confirm once the call has run. Everyone marked present gets attendance, and for
            an onboarding call that is what completes the learner&rsquo;s onboarding.
          </p>

          <div>
            <div className="form-label">Booked</div>
            {booked.length === 0 ? (
              <p className="small muted mb-0">Nobody booked this session.</p>
            ) : booked.map((b) => (
              <label key={b.learnerId} className="d-flex gap-2 align-items-center mb-1">
                <input type="checkbox" checked={!absent[b.learnerId]}
                  onChange={(e) => setAbsent((a) => ({ ...a, [b.learnerId]: !e.target.checked }))} />
                <span>{b.name}</span>
                <span className="tiny muted">{absent[b.learnerId] ? 'absent' : 'present'}</span>
              </label>
            ))}
          </div>

          {opts.candidates.length > 0 && (
            <div>
              <div className="form-label">
                {booked.length === 0 ? 'Who attended?' : 'Anyone else who attended without booking'}
              </div>
              {opts.candidates.length > 8 && (
                <input className="form-control form-control-sm mb-2" placeholder="Find a learner"
                  value={q} onChange={(e) => setQ(e.target.value)} aria-label="Find a learner" />
              )}
              <div style={{ maxHeight: 260, overflowY: 'auto' }}>
                {candidates.map((c) => (
                  <label key={c.learnerId} className="d-flex gap-2 align-items-center mb-1">
                    <input type="checkbox" checked={!!extra[c.learnerId]}
                      onChange={(e) => setExtra((x) => ({ ...x, [c.learnerId]: e.target.checked }))} />
                    <span>{c.name}</span>
                    {c.awaitingCall && <span className="tag tag-wait">awaiting call</span>}
                  </label>
                ))}
                {candidates.length === 0 && <p className="small muted mb-0">No learner matches that.</p>}
              </div>
            </div>
          )}
        </div>
      )}
    </Drawer>
  )
}
