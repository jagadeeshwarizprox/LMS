import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { useToast } from '../context/ToastContext'
import { Card, Switch, Tag } from './Ui'

/**
 * Features for this one learner. Their track decides the default; an override here
 * wins over it, and "Use track default" removes the override again. Every change is
 * kept in the activity log.
 */
export default function LearnerFeaturesPanel({ learnerId }) {
  const toast = useToast()
  const [rows, setRows] = useState(null)

  const load = () => api.get(`/admin/learners/${learnerId}/features`)
    .then(setRows).catch(() => setRows(null))
  useEffect(() => { load() }, [learnerId])
  if (!rows) return null

  const set = async (f, enabled) => {
    try {
      await api.post(`/admin/learners/${learnerId}/features/${f.key}`, { enabled })
      toast.push(`${f.label}: ${enabled ? 'on' : 'off'} for this learner.`)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const reset = async (f) => {
    try {
      await api.del(`/admin/learners/${learnerId}/features/${f.key}`)
      toast.push(`${f.label}: back to the track default.`)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const overridden = rows.filter((f) => f.override !== null && f.override !== undefined).length

  return (
    <Card
      title="Features for this learner"
      note={overridden
        ? `${overridden} set by hand. Everything else follows their track.`
        : 'Everything follows their track. Switch one to set it by hand.'}
    >
      <table className="table table-pib mb-0">
        <thead><tr><th>Feature</th><th>Track gives</th><th>For this learner</th><th /></tr></thead>
        <tbody>
          {rows.map((f) => {
            const isSet = f.override !== null && f.override !== undefined
            return (
              <tr key={f.key}>
                <td>{f.label}</td>
                <td>{f.trackDefault ? <Tag kind="ok">On</Tag> : <Tag kind="wait">Off</Tag>}</td>
                <td>
                  <Switch checked={f.effective} onChange={(v) => set(f, v)} />
                  {isSet && <Tag kind="batch">Set by hand</Tag>}
                </td>
                <td className="text-end">
                  {isSet && <button className="btn btn-s" onClick={() => reset(f)}>Use track default</button>}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </Card>
  )
}
