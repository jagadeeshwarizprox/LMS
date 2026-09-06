import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import { Card, Empty, Tag, fmtDateTime } from './Ui'

/**
 * Every way a learner moves, in one panel, so an admin is not hunting across four
 * screens. Nothing here deletes anything: a learner who changes track, batch or
 * course keeps their progress, tasks, tests and history, because they have not
 * stopped being the same person.
 */
export default function MovePanel({ learnerId, onMoved }) {
  const toast = useToast()
  const { user } = useAuth()
  const [opts, setOpts] = useState(null)
  const [busy, setBusy] = useState(false)
  const [form, setForm] = useState({ kind: '', batchId: '', bundleId: '', reason: '' })

  /*
   * Moving a learner between tracks, batches and courses is an admin action, and this
   * panel asks an admin-only endpoint for its options. It used to ask on every render
   * of the learner record, including when a mentor opened it, with no catch on the
   * promise: a mentor's own learner produced a refused request they never asked for.
   * Staff ask; everybody else does not, and a refusal is handled rather than thrown.
   */
  const staff = user?.role === 'ADMIN' || user?.role === 'SUPER_ADMIN'

  const load = () => {
    if (!staff) return
    api.get(`/admin/learners/${learnerId}/move-options`).then(setOpts).catch(() => setOpts(null))
  }
  useEffect(() => { load() }, [learnerId, staff])
  if (!staff || !opts) return null

  const run = async (fn, msg) => {
    if (!form.reason.trim()) { toast.push('A reason is needed. It stays on the record.', 'bad'); return }
    setBusy(true)
    try {
      const r = await fn()
      toast.push(r?.note ? `${msg} ${r.note}` : msg)
      setForm({ kind: '', batchId: '', bundleId: '', reason: '' })
      await load()
      onMoved?.()
    } catch (e) {
      toast.push(e.message, 'bad')
    } finally {
      setBusy(false)
    }
  }

  const toPremium = () => run(
    () => api.post(`/admin/learners/${learnerId}/track`, { trackType: 'PREMIUM', reason: form.reason }),
    'Moved to premium. Progress and mentor kept, onboarding not re-gated.')

  const toBatch = () => run(
    () => api.post(`/admin/learners/${learnerId}/track`, {
      trackType: 'BATCH', batchId: form.batchId, reason: form.reason
    }), 'Moved to the batch track.')

  const moveBatch = () => run(
    () => api.post(`/admin/learners/${learnerId}/move-batch`, {
      batchId: form.batchId, reason: form.reason
    }), 'Moved to the new batch.')

  const moveCourse = () => run(
    () => api.post(`/admin/learners/${learnerId}/course`, {
      bundleId: form.bundleId, reason: form.reason
    }), 'Course changed.')

  const hold = () => run(
    () => api.post(`/admin/learners/${learnerId}/hold`, { reason: form.reason }),
    'On hold. No nudges and no at risk flags until they resume.')

  const resume = async () => {
    setBusy(true)
    try {
      await api.post(`/admin/learners/${learnerId}/resume`, {})
      toast.push('Back to active.')
      await load(); onMoved?.()
    } catch (e) { toast.push(e.message, 'bad') } finally { setBusy(false) }
  }

  const premium = opts.trackType === 'PREMIUM'

  return (
    <Card
      title="Move this learner"
      note="Nothing is deleted by a move. Progress, tasks, tests and history follow the learner."
      actions={opts.onHold ? <Tag kind="batch">On hold</Tag> : null}
    >
      <div className="row g-2 mb-3">
        <div className="col-md-4">
          <label className="form-label">Currently</label>
          <div className="move-now">
            <Tag kind={premium ? 'premium' : 'batch'}>{premium ? 'Premium' : 'Batch'}</Tag>
            {opts.currentBatch && <span className="mono ms-2">{opts.currentBatch}</span>}
            <div className="small muted mt-1">{opts.currentBundle || 'No course set'}</div>
          </div>
        </div>
        <div className="col-md-8">
          <label className="form-label">Reason, kept on the record</label>
          <input
            className="form-control"
            placeholder="Why is this learner moving?"
            value={form.reason}
            onChange={(e) => setForm((f) => ({ ...f, reason: e.target.value }))}
          />
        </div>
      </div>

      <div className="move-grid">
        {!premium && (
          <div className="move-option">
            <div className="move-title">To premium</div>
            <div className="move-note">Batch dropped, one to one features open, onboarding not re-gated.</div>
            <button className="btn btn-pib" onClick={toPremium} disabled={busy}>Move to premium</button>
          </div>
        )}

        <div className="move-option">
          <div className="move-title">{premium ? 'To a batch' : 'To another batch'}</div>
          <div className="move-note">
            {opts.suggestedBatch && <>Suggested by the Tuesday rule: <strong>{opts.suggestedBatch}</strong>. </>}
            Induction re-applies only if that batch has not held it.
          </div>
          <div className="d-flex gap-2">
            <select
              className="form-select"
              value={form.batchId}
              onChange={(e) => setForm((f) => ({ ...f, batchId: e.target.value }))}
            >
              <option value="">Choose a batch</option>
              {opts.batches.map((b) => (
                <option key={b.id} value={b.id}>
                  {b.code} · starts {b.startDate} · {b.size} learners
                  {b.inductionDone ? ' · induction held' : ''}
                </option>
              ))}
            </select>
            <button
              className="btn btn-pib"
              onClick={premium ? toBatch : moveBatch}
              disabled={busy || !form.batchId}
            >
              Move
            </button>
          </div>
        </div>

        <div className="move-option">
          <div className="move-title">Change course</div>
          <div className="move-note">Shared modules keep their progress. The rest is kept and returns if you move back.</div>
          <div className="d-flex gap-2">
            <select
              className="form-select"
              value={form.bundleId}
              onChange={(e) => setForm((f) => ({ ...f, bundleId: e.target.value }))}
            >
              <option value="">Choose a course</option>
              {opts.bundles.map((b) => (
                <option key={b.id} value={b.id}>{b.name} · {b.modules} modules</option>
              ))}
            </select>
            <button className="btn btn-pib" onClick={moveCourse} disabled={busy || !form.bundleId}>Move</button>
          </div>
        </div>

        <div className="move-option">
          <div className="move-title">{opts.onHold ? 'Resume' : 'Put on hold'}</div>
          <div className="move-note">
            {opts.onHold
              ? `On hold: ${opts.holdReason || 'no reason recorded'}`
              : 'No nudges, no at risk flags, modules frozen. Life happens.'}
          </div>
          {opts.onHold
            ? <button className="btn btn-pib" onClick={resume} disabled={busy}>Resume learning</button>
            : <button className="btn btn-quiet" onClick={hold} disabled={busy}>Put on hold</button>}
        </div>
      </div>

      <div className="eyebrow mt-4 mb-2">Every move on this learner</div>
      {opts.history.length === 0 ? (
        <Empty title="No moves yet" />
      ) : (
        <table className="table table-pib mb-0">
          <thead><tr><th>When</th><th>What</th><th>From</th><th>To</th><th>Reason</th><th>By</th></tr></thead>
          <tbody>
            {opts.history.map((h, i) => (
              <tr key={i}>
                <td className="mono">{fmtDateTime(h.at)}</td>
                <td>{h.kind}</td>
                <td className="small">{h.from}</td>
                <td className="small">{h.to}</td>
                <td className="small">{h.reason}</td>
                <td className="mono small">{h.by}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Card>
  )
}
