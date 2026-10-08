import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { useDialog } from '../../components/Dialog'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, LoadError, Page, Switch, Tag } from '../../components/Ui'

const SCOPES = [['TASK', 'Assignments'], ['PROJECT', 'Projects'], ['MOCK', 'Mock interviews']]
const blankCriterion = () => ({ key: '', label: '', guidance: '', weight: 1 })
const blank = () => ({ name: '', scope: 'TASK', active: true, criteria: [blankCriterion()] })

/** "Code quality" becomes code_quality: the key is stored on every score, so it stays stable. */
const keyOf = (label) => label.trim().toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, '')

/**
 * The rubrics work is marked against.
 *
 * Two were seeded and there was no way to change a word of them, so a mentor marking
 * a project used the task rubric because it was the only one with the right name.
 * Switching one off hides it from new work; anything already marked keeps its scores.
 */
export default function Rubrics() {
  const toast = useToast()
  const { ask } = useDialog()
  const [rows, setRows] = useState(null)
  const [error, setError] = useState(null)
  const [draft, setDraft] = useState(null)

  const load = () => api.get('/super/catalogue/rubrics')
    .then((r) => { setRows(r); setError(null) })
    .catch((e) => setError(e.message))
  useEffect(() => { load() }, [])

  if (error) return <Page title="Rubrics"><LoadError error={error} onRetry={load} /></Page>
  if (!rows) return <TableSkeleton />

  const setC = (i, k, v) => setDraft((d) => ({
    ...d,
    criteria: d.criteria.map((c, j) => (j === i ? { ...c, [k]: v } : c))
  }))

  const save = async () => {
    const criteria = draft.criteria
      .filter((c) => c.label.trim())
      .map((c) => ({ ...c, key: c.key || keyOf(c.label), weight: Number(c.weight) || 1 }))
    if (!draft.name.trim()) { toast.push('Give the rubric a name.', 'bad'); return }
    if (criteria.length === 0) { toast.push('Add at least one criterion.', 'bad'); return }
    try {
      await api.post('/super/catalogue/rubrics', { ...draft, name: draft.name.trim(), criteria })
      toast.push('Rubric saved.')
      setDraft(null)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const setActive = async (r, active) => {
    try {
      await api.post('/super/catalogue/rubrics', { ...r, active })
      toast.push(active ? `${r.name} is back in use.` : `${r.name} is switched off for new work.`)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const remove = async (r) => {
    const ok = await ask({
      title: `Delete ${r.name}?`,
      intent: 'danger',
      body: 'A chapter or project that names this rubric will lose it. Switching it off is '
        + 'usually what you want: it disappears from new work and nothing breaks.',
      confirmWord: 'delete',
      confirmLabel: 'Delete'
    })
    if (!ok) return
    try {
      await api.del(`/super/catalogue/rubrics/${r.id}`)
      toast.push('Deleted.')
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const total = (r) => (r.criteria || []).reduce((a, c) => a + (Number(c.weight) || 0), 0)

  return (
    <Page
      title="Rubrics"
      lede="What assignments, projects and mock interviews are marked against."
      actions={!draft && <button className="btn btn-pib" onClick={() => setDraft(blank())}>New rubric</button>}
    >
      {draft && (
        <Card title={draft.id ? `Edit ${draft.name}` : 'New rubric'}
          note="Weights are relative: a criterion weighted 2 counts twice as much as one weighted 1.">
          <div className="row g-2 mb-3">
            <div className="col-md-8">
              <label className="form-label">Name</label>
              <input className="form-control" value={draft.name}
                onChange={(e) => setDraft((d) => ({ ...d, name: e.target.value }))} />
            </div>
            <div className="col-md-4">
              <label className="form-label">Used for</label>
              <select className="form-select" value={draft.scope}
                onChange={(e) => setDraft((d) => ({ ...d, scope: e.target.value }))}>
                {SCOPES.map(([k, l]) => <option key={k} value={k}>{l}</option>)}
              </select>
            </div>
          </div>

          <div className="eyebrow mb-2">Criteria</div>
          {draft.criteria.map((c, i) => (
            <div className="row g-2 mb-2 align-items-end" key={i}>
              <div className="col-md-3">
                <label className="form-label small">Criterion</label>
                <input className="form-control" value={c.label} placeholder="Code quality"
                  onChange={(e) => setC(i, 'label', e.target.value)} />
              </div>
              <div className="col-md-6">
                <label className="form-label small">What good looks like</label>
                <input className="form-control" value={c.guidance}
                  onChange={(e) => setC(i, 'guidance', e.target.value)} />
              </div>
              <div className="col-md-1">
                <label className="form-label small">Weight</label>
                <input className="form-control" type="number" min={1} max={10} value={c.weight}
                  onChange={(e) => setC(i, 'weight', e.target.value)} />
              </div>
              <div className="col-md-2 text-end">
                <button className="btn btn-quiet" disabled={draft.criteria.length === 1}
                  onClick={() => setDraft((d) => ({ ...d, criteria: d.criteria.filter((_, j) => j !== i) }))}>
                  Remove
                </button>
              </div>
            </div>
          ))}
          <div className="d-flex gap-2 mt-3">
            <button className="btn btn-quiet"
              onClick={() => setDraft((d) => ({ ...d, criteria: [...d.criteria, blankCriterion()] }))}>
              Add criterion
            </button>
            <span className="flex-grow-1" />
            <button className="btn btn-quiet" onClick={() => setDraft(null)}>Cancel</button>
            <button className="btn btn-pib" onClick={save}>Save rubric</button>
          </div>
        </Card>
      )}

      {SCOPES.map(([scope, label]) => {
        const group = rows.filter((r) => (r.scope || 'TASK') === scope)
        return (
          <Card key={scope} title={label} note={`${group.length} rubric${group.length === 1 ? '' : 's'}`}>
            {group.length === 0 ? <Empty title="None yet" /> : (
              <table className="table table-pib mb-0">
                <thead><tr><th>Rubric</th><th>Criteria</th><th>In use</th><th /></tr></thead>
                <tbody>
                  {group.map((r) => (
                    <tr key={r.id}>
                      <td>
                        <strong>{r.name}</strong>
                        {!r.active && <> <Tag kind="wait">Off</Tag></>}
                      </td>
                      <td className="small">
                        {(r.criteria || []).map((c) => `${c.label} (${c.weight})`).join(' · ')}
                        <div className="muted">Total weight {total(r)}</div>
                      </td>
                      <td><Switch checked={r.active} onChange={(v) => setActive(r, v)} /></td>
                      <td className="text-end">
                        <div className="row-actions">
                          <button className="btn btn-s" onClick={() => setDraft({
                            ...r, criteria: (r.criteria || []).length ? r.criteria.map((c) => ({ ...c })) : [blankCriterion()]
                          })}>Edit</button>
                          <button className="btn btn-s" onClick={() => remove(r)}>Delete</button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>
        )
      })}
    </Page>
  )
}
