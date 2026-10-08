import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { useToast } from '../context/ToastContext'
import { Card, Tag } from './Ui'

/**
 * Modules a learner already knows, marked review only. They stay open to watch, but
 * the weekly plan and the at risk sweep stop counting them as work still owed.
 *
 * The intake form asks for a self rating per subject, and anything rated Advanced is
 * offered here as a suggestion. Nothing is applied until an admin saves.
 */
export default function FastForwardPanel({ learnerId }) {
  const toast = useToast()
  const [data, setData] = useState(null)
  const [picked, setPicked] = useState(new Set())
  const [busy, setBusy] = useState(false)

  const load = () => api.get(`/admin/learners/${learnerId}/fast-forward`)
    .then((d) => { setData(d); setPicked(new Set(d.applied || [])) })
    .catch(() => setData(null))
  useEffect(() => { load() }, [learnerId])
  if (!data) return null

  const suggested = new Set((data.suggested || []).map((s) => s.moduleId))
  const flip = (id) => setPicked((prev) => {
    const next = new Set(prev)
    next.has(id) ? next.delete(id) : next.add(id)
    return next
  })
  const changed = (data.applied || []).length !== picked.size
    || (data.applied || []).some((x) => !picked.has(x))

  const save = async () => {
    setBusy(true)
    try {
      await api.post(`/admin/learners/${learnerId}/fast-forward`, { moduleIds: [...picked] })
      toast.push(picked.size ? `${picked.size} marked review only.` : 'Nothing is review only now.')
      await load()
    } catch (e) { toast.push(e.message, 'bad') } finally { setBusy(false) }
  }

  return (
    <Card
      title="Fast forward"
      note="Review only modules stay open to watch but stop counting as work still owed."
      actions={
        <button className="btn btn-pib" onClick={save} disabled={busy || !changed}>Save</button>
      }
    >
      {data.modules.length === 0 ? (
        <p className="text-muted small mb-0">No modules in the catalogue yet.</p>
      ) : (
        <div className="d-flex flex-wrap gap-2">
          {data.modules.map((m) => (
            <label key={m.id} className={`ff-chip ${picked.has(m.id) ? 'on' : ''}`}>
              <input type="checkbox" checked={picked.has(m.id)} onChange={() => flip(m.id)} />
              <span>{m.name}</span>
              {suggested.has(m.id) && <Tag kind="wait" title="Rated Advanced in the intake form">Suggested</Tag>}
            </label>
          ))}
        </div>
      )}
    </Card>
  )
}
