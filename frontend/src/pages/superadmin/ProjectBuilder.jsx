import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { Card, Empty, LoadError, Page, Tag } from '../../components/Ui'
import { TableSkeleton } from '../../components/Skeletons'
import Drawer from '../../components/Drawer'
import FileUpload from '../../components/FileUpload'
import { useToast } from '../../context/ToastContext'

/**
 * Authoring a project.
 *
 * The stages are fixed at six for every project. A mentor learns one flow and a batch
 * grid compares across projects; per-project stages would be more flexible and much
 * harder to run a cohort against. What an author changes is what each stage asks for,
 * when it is due, and which rubric scores it.
 */
export default function ProjectBuilder() {
  const toast = useToast()
  const [rows, setRows] = useState(null)
  const [rubrics, setRubrics] = useState([])
  const [bundles, setBundles] = useState([])
  const [error, setError] = useState(null)
  const [edit, setEdit] = useState(null)

  const load = () => {
    setError(null)
    api.get('/super/catalogue/projects').then(setRows).catch(setError)
  }

  useEffect(() => {
    load()
    api.get('/super/catalogue/rubrics').then(setRubrics).catch(() => setRubrics([]))
    api.get('/super/catalogue').then((r) => setBundles(r.bundles || [])).catch(() => setBundles([]))
  }, [])

  const blank = () => ({
    title: '', subtitle: '', brief: '', clientContext: '', successCriteria: '',
    trackScope: 'BOTH', difficulty: 'INTERMEDIATE', expectedHours: 20,
    teamAllowed: false, teamSize: 1, bundleIds: [], resourceFileIds: [],
    resourceLinks: [], stages: [], active: true
  })

  if (error) return <LoadError error={error} onRetry={load} />

  return (
    <Page
      title="Projects"
      lede="Real work the organisation briefs and teaches, authored once and run by every batch."
      actions={<button className="btn" onClick={() => setEdit(blank())}>New project</button>}
    >
      {!rows ? <TableSkeleton /> : rows.length === 0 ? (
        <Empty title="No projects yet" icon="projects">
          A project is a brief, some data, and six stages a learner works through with a mentor.
        </Empty>
      ) : (
        <Card>
          <div className="table-responsive">
            <table className="table table-pib mb-0">
              <thead>
                <tr><th>Project</th><th>Track</th><th>Level</th><th>Hours</th><th>Stages</th><th /></tr>
              </thead>
              <tbody>
                {rows.map((p) => (
                  <tr key={p.id}>
                    <td>
                      <strong>{p.title}</strong>
                      <span className="sub">{p.subtitle}</span>
                    </td>
                    <td>{p.trackScope === 'BOTH' ? 'Premium and batch' : p.trackScope.toLowerCase()}</td>
                    <td>{p.difficulty.toLowerCase()}</td>
                    <td className="mono">{p.expectedHours || '—'}</td>
                    <td className="mono">{p.stages?.length || 0}</td>
                    <td className="text-end">
                      {!p.active && <Tag kind="wait">Retired</Tag>}
                      <button className="btn-quiet sm" onClick={() => setEdit(p)}>Edit</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {edit && (
        <ProjectEditor
          value={edit}
          rubrics={rubrics.filter((r) => r.scope === 'PROJECT' || !r.scope)}
          bundles={bundles}
          onClose={() => setEdit(null)}
          onSaved={() => { setEdit(null); load(); toast.push('Project saved.') }}
        />
      )}
    </Page>
  )
}

function ProjectEditor({ value, rubrics, bundles, onClose, onSaved }) {
  const toast = useToast()
  const [p, setP] = useState(value)
  const [busy, setBusy] = useState(false)
  const set = (k, v) => setP((prev) => ({ ...prev, [k]: v }))

  const setStage = (i, k, v) => setP((prev) => ({
    ...prev,
    stages: prev.stages.map((s, n) => (n === i ? { ...s, [k]: v } : s))
  }))

  const save = async () => {
    if (!p.title.trim()) { toast.push('Give the project a title.', 'bad'); return }
    setBusy(true)
    try {
      await api.post('/super/catalogue/projects', p)
      onSaved()
    } catch (e) {
      toast.push(e.message, 'bad')
    } finally {
      setBusy(false)
    }
  }

  return (
    /*
     * Wide, and split into named sections.
     *
     * Six stages, each with a brief, a due offset and a rubric, does not fit in a narrow
     * column: you end up scrolling a form that would sit on one screen twice over, and
     * losing your place between the stage you are writing and the one before it.
     */
    <Drawer
      open
      wide
      title={p.id ? 'Edit project' : 'New project'}
      subtitle={p.id ? p.title : 'The six stages are added for you'}
      onClose={onClose}
      footer={
        <>
          <button className="btn-quiet" onClick={onClose}>Cancel</button>
          <button className="btn" disabled={busy} onClick={save}>{busy ? 'Saving' : 'Save project'}</button>
        </>
      }
    >
      <div className="form-grid form-grid-2">
        <p className="form-section">The project</p>
        <label className="wide">
          <span>Title</span>
          <input value={p.title} onChange={(e) => set('title', e.target.value)} />
        </label>
        <label className="wide">
          <span>One line</span>
          <input
            value={p.subtitle || ''} placeholder="What it is, in a sentence"
            onChange={(e) => set('subtitle', e.target.value)}
          />
        </label>

        <label className="wide">
          <span>The brief</span>
          <textarea
            rows={7} value={p.brief || ''}
            placeholder="The problem, as a client would put it. Not a list of tasks."
            onChange={(e) => set('brief', e.target.value)}
          />
        </label>

        <label className="wide">
          <span>Who the work is for</span>
          <textarea
            rows={3} value={p.clientContext || ''}
            placeholder="Give them somebody to write for. A real company, or a plausible one."
            onChange={(e) => set('clientContext', e.target.value)}
          />
        </label>

        <label className="wide">
          <span>What finished looks like</span>
          <textarea
            rows={3} value={p.successCriteria || ''}
            placeholder="In your words, not theirs. This is what the last review is against."
            onChange={(e) => set('successCriteria', e.target.value)}
          />
        </label>

        <label>
          <span>Who it is for</span>
          <select value={p.trackScope} onChange={(e) => set('trackScope', e.target.value)}>
            <option value="BOTH">Premium and batch</option>
            <option value="PREMIUM">Premium only</option>
            <option value="BATCH">Batch only</option>
          </select>
        </label>

        <label>
          <span>Level</span>
          <select value={p.difficulty} onChange={(e) => set('difficulty', e.target.value)}>
            <option value="BEGINNER">Beginner</option>
            <option value="INTERMEDIATE">Intermediate</option>
            <option value="ADVANCED">Advanced</option>
          </select>
        </label>

        <label>
          <span>Expected hours</span>
          <input
            type="number" min="1" value={p.expectedHours || 0}
            onChange={(e) => set('expectedHours', Number(e.target.value))}
          />
        </label>

        <label>
          <span>Courses it belongs to</span>
          <select
            multiple value={p.bundleIds || []}
            onChange={(e) => set('bundleIds', [...e.target.selectedOptions].map((o) => o.value))}
          >
            {bundles.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
          </select>
        </label>

        {/*
          * Teams default to off. A team of four hiding one person who did nothing is the
          * commonest failure in cohort projects, and it needs per-person visibility
          * before it is worth turning on.
          */}
        <label className="check wide">
          <input
            type="checkbox" checked={p.teamAllowed}
            onChange={(e) => set('teamAllowed', e.target.checked)}
          />
          <span>Allow teams. Leave this off until a mentor can see who did what.</span>
        </label>
      </div>

      <div className="proj-editor-files">
        <span className="brief-label">Data and starter files</span>
        <FileUpload purpose="PROJECT_RESOURCE" onUploaded={(f) =>
          set('resourceFileIds', [...(p.resourceFileIds || []), f.id])} />
        {p.resourceFileIds?.length > 0 && (
          <p className="small muted">
            {p.resourceFileIds.length} file{p.resourceFileIds.length === 1 ? '' : 's'} attached.
          </p>
        )}
      </div>

      {p.stages?.length > 0 && (
        <div className="proj-editor-stages">
          <p className="form-section">The six stages</p>
          {p.stages.map((s, i) => (
            <div className="proj-editor-stage" key={s.key}>
              <div className="proj-editor-stage-head">
                <span className="mono">{i + 1}</span>
                <strong>{s.name}</strong>
                {!s.submittable && <Tag>Nothing handed in</Tag>}
              </div>

              <label>
                <span>What this stage asks for</span>
                <textarea rows={2} value={s.asks || ''} onChange={(e) => setStage(i, 'asks', e.target.value)} />
              </label>

              <div className="form-grid form-grid-2">
                <label>
                  <span>Due, days from starting</span>
                  <input
                    type="number" min="0" value={s.dueOffsetDays}
                    onChange={(e) => setStage(i, 'dueOffsetDays', Number(e.target.value))}
                  />
                </label>
                {s.submittable && (
                  <label>
                    <span>Scored with</span>
                    <select value={s.rubricId || ''} onChange={(e) => setStage(i, 'rubricId', e.target.value)}>
                      <option value="">No rubric</option>
                      {rubrics.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
                    </select>
                  </label>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      {!p.id && (
        <p className="small muted">
          The six stages are added when you save: brief, scope, two build checkpoints, review, demo.
        </p>
      )}
    </Drawer>
  )
}
