import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Page, Tag } from '../../components/Ui'

const PLACEMENTS = [
  ['LOGIN', 'Sign in screen'], ['GATES', 'Onboarding card'], ['CHAPTER', 'Chapter player'],
  ['SESSIONS', 'Sessions and recordings'], ['MOCK', 'Mock interviews'],
  ['BATCH', 'Batch space'], ['PROFILE', 'Profile'], ['HELP', 'Help']
]

export default function Faqs() {
  const toast = useToast()
  const [rows, setRows] = useState(null)
  const [draft, setDraft] = useState({ question: '', answer: '', placement: 'HELP', trackScope: 'BOTH' })

  const load = () => api.get('/faqs').then(setRows)
  useEffect(() => { load() }, [])
  if (!rows) return <TableSkeleton />

  const save = async () => {
    try {
      await api.post('/super/faqs', { ...draft, position: rows.length })
      toast.push('Saved.')
      setDraft({ question: '', answer: '', placement: 'HELP', trackScope: 'BOTH' })
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const remove = async (id) => {
    try { await api.del(`/super/faqs/${id}`); await load() }
    catch (e) { toast.push(e.message, 'bad') }
  }

  const set = (k) => (e) => setDraft((d) => ({ ...d, [k]: e.target.value }))

  return (
    <Page title="Questions and answers"
      lede="Short answers learners see on the sign in screen and inside the app.">
      <Card title="Add one" note="Placement decides where it appears, so it reaches the learner at the moment they wonder.">
        <div className="row g-2">
          <div className="col-md-6">
            <label className="form-label">Question</label>
            <input className="form-control" value={draft.question} onChange={set('question')} />
          </div>
          <div className="col-md-3">
            <label className="form-label">Shown on</label>
            <select className="form-select" value={draft.placement} onChange={set('placement')}>
              {PLACEMENTS.map(([k, l]) => <option key={k} value={k}>{l}</option>)}
            </select>
          </div>
          <div className="col-md-3">
            <label className="form-label">Track</label>
            <select className="form-select" value={draft.trackScope} onChange={set('trackScope')}>
              <option value="BOTH">Both</option>
              <option value="PREMIUM">Premium</option>
              <option value="BATCH">Batch</option>
            </select>
          </div>
          <div className="col-12">
            <label className="form-label">Answer</label>
            <textarea className="form-control" rows={2} value={draft.answer} onChange={set('answer')} />
          </div>
          <div className="col-12">
            <button className="btn btn-pib" onClick={save} disabled={!draft.question || !draft.answer}>Add</button>
          </div>
        </div>
      </Card>

      {PLACEMENTS.map(([key, label]) => {
        const group = rows.filter((f) => f.placement === key)
        if (group.length === 0) return null
        return (
          <Card key={key} title={label} note={`${group.length} shown here`}>
            <table className="table table-pib mb-0">
              <tbody>
                {group.map((f) => (
                  <tr key={f.id}>
                    <td style={{ width: '34%' }}>{f.question}</td>
                    <td className="small muted">{f.answer}</td>
                    <td style={{ width: 90 }}>
                      {f.trackScope !== 'BOTH' && <Tag kind="batch">{f.trackScope}</Tag>}
                    </td>
                    <td className="text-end" style={{ width: 90 }}>
                      <button className="btn btn-quiet" onClick={() => remove(f.id)}>Remove</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
        )
      })}
    </Page>
  )
}
