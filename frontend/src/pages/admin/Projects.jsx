import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { useDialog } from '../../components/Dialog'
import { TableSkeleton } from '../../components/Skeletons'
import Avatar from '../../components/Avatar'
import { Card, Empty, LoadError, Page, Stat } from '../../components/Ui'

/**
 * Putting people on a project.
 *
 * Super admin writes the brief; nothing happens until somebody is enrolled, and there was
 * no screen that did it. Four working endpoints sat unreachable, which meant the learner
 * workspace and the mentor review queue were both permanently empty however good the
 * catalogue was.
 *
 * Enrolment is an operations decision, the same shape as a batch move, which is why it is
 * here rather than in the builder. A cohort goes on in one action. A premium learner goes
 * on one at a time, because premium is paced to the person.
 */

const CELL = {
  APPROVED: ['ok', 'Approved'],
  SUBMITTED: ['wait', 'Waiting on the mentor'],
  CHANGES: ['stop', 'Sent back'],
  OPEN: ['open', 'In progress'],
  LOCKED: ['locked', 'Not open yet']
}

export default function Projects() {
  const toast = useToast()
  const { ask } = useDialog()

  const [projects, setProjects] = useState(null)
  const [batches, setBatches] = useState([])
  const [error, setError] = useState(null)
  const [grid, setGrid] = useState(null)
  const [gridKey, setGridKey] = useState(null)

  const load = () => {
    setError(null)
    Promise.all([
      api.get('/admin/projects'),
      api.get('/admin/batches').catch(() => [])
    ]).then(([p, b]) => { setProjects(p); setBatches(b) })
      .catch((e) => setError(e.message))
  }

  useEffect(load, [])

  if (error) return <Page title="Projects"><LoadError error={error} onRetry={load} /></Page>
  if (!projects) return <TableSkeleton />

  const enrolBatch = async (p) => {
    if (batches.length === 0) {
      toast.push('There are no batches to enrol.', 'bad')
      return
    }
    const r = await ask({
      title: `Put a cohort on ${p.title}`,
      body: 'Everyone in the batch is enrolled and mailed. Anyone already on this project is '
          + 'skipped, so running it again after a batch move is safe.',
      fields: [{
        name: 'batchId',
        label: 'Batch',
        options: batches.map((b) => ({
          value: b.id,
          label: `${b.code} — ${b.learners || 0} learners`
        }))
      }],
      confirmLabel: 'Enrol the cohort'
    })
    if (!r || !r.batchId) return
    try {
      const out = await api.post(`/admin/projects/${p.id}/enrol-batch`, { batchId: r.batchId })
      toast.push(`${out.enrolled} of ${out.cohort} enrolled.`)
      if (gridKey) openGrid(gridKey.project, gridKey.batchId)
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const enrolOne = async (p) => {
    const r = await ask({
      title: `Put one learner on ${p.title}`,
      body: 'Use this for premium, where the project is paced to the person rather than to a '
          + 'cohort. The learner is mailed the brief.',
      fields: [{
        name: 'learnerId',
        label: 'Learner id',
        hint: 'Copy it from the address bar of their record, /mentor/learners/<id>'
      }],
      confirmLabel: 'Enrol'
    })
    if (!r || !r.learnerId) return
    try {
      await api.post(`/admin/projects/${p.id}/enrol-learner`, { learnerId: r.learnerId.trim() })
      toast.push('Enrolled. The brief is on their way.')
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const openGrid = async (p, batchId) => {
    setGridKey({ project: p, batchId })
    setGrid(null)
    try {
      setGrid(await api.get(`/admin/projects/${p.id}/batches/${batchId}/grid`))
    } catch (e) {
      setGrid({ error: e.message })
    }
  }

  const pickGrid = async (p) => {
    if (batches.length === 0) return
    const r = await ask({
      title: `Where is ${p.title} up to?`,
      fields: [{
        name: 'batchId',
        label: 'Batch',
        options: batches.map((b) => ({ value: b.id, label: b.code }))
      }],
      confirmLabel: 'Show the grid'
    })
    if (!r || !r.batchId) return
    openGrid(p, r.batchId)
  }

  return (
    <Page
      title="Projects"
      lede="Who is on which project, and how far the cohort has got."
      actions={<button className="btn-quiet" onClick={load}>Refresh</button>}
    >
      <div className="row g-3 mb-4">
        <div className="col-6 col-lg-3">
          <Stat value={projects.length} label="Live projects" icon="projects" />
        </div>
        <div className="col-6 col-lg-3">
          <Stat value={batches.length} label="Batches" icon="batch" />
        </div>
      </div>

      {projects.length === 0 ? (
        <Empty title="No projects yet" icon="projects">
          A project is written under Super admin, Projects. Once one is live it can be given
          to a cohort from here.
        </Empty>
      ) : (
        <Card>
          <div className="table-responsive">
            <table className="table table-pib mb-0">
              <thead>
                <tr>
                  <th>Project</th><th>Level</th><th>Stages</th><th>Hours</th><th />
                </tr>
              </thead>
              <tbody>
                {projects.map((p) => (
                  <tr key={p.id}>
                    <td>
                      <strong>{p.title}</strong>
                      {p.clientContext && (
                        <div className="small muted">{p.clientContext}</div>
                      )}
                    </td>
                    <td>{p.level || '—'}</td>
                    <td className="mono">{p.stages?.length || 0}</td>
                    <td className="mono">{p.expectedHours || '—'}</td>
                    <td className="text-end text-nowrap">
                      <button className="btn btn-quiet me-2" onClick={() => pickGrid(p)}>
                        Grid
                      </button>
                      <button className="btn btn-quiet me-2" onClick={() => enrolOne(p)}>
                        One learner
                      </button>
                      <button className="btn btn-pib" onClick={() => enrolBatch(p)}>
                        Enrol a cohort
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {gridKey && (
        <Card
          className="mt-4"
          title={`${gridKey.project.title}`}
          note="A column of amber is a stage the mentor is behind on. A row of grey is somebody who stopped."
          actions={<button className="btn-quiet" onClick={() => { setGrid(null); setGridKey(null) }}>Close</button>}
        >
          {!grid ? <TableSkeleton /> : grid.error ? (
            <div className="alert-quiet">{grid.error}</div>
          ) : grid.rows.length === 0 ? (
            <Empty title="Nobody on it yet" icon="projects">
              This batch has not been enrolled on this project.
            </Empty>
          ) : (
            <>
              <div className="row g-3 mb-3">
                <div className="col-6 col-lg-3">
                  <Stat value={grid.rows.length} label="On the project" />
                </div>
                <div className="col-6 col-lg-3">
                  <Stat value={grid.waiting} label="Waiting on a mentor" />
                </div>
                <div className="col-6 col-lg-3">
                  <Stat value={grid.approved} label="Finished" />
                </div>
              </div>
              <div className="table-responsive">
                <table className="table table-pib proj-grid mb-0">
                  <thead>
                    <tr>
                      <th>Learner</th>
                      {grid.stages.map((s) => <th key={s.key}>{s.name}</th>)}
                    </tr>
                  </thead>
                  <tbody>
                    {grid.rows.map((r) => (
                      <tr key={r.runId}>
                        <td>
                          <span className="who-cell">
                            <Avatar name={r.learner} size={28} />
                            <Link to={`/mentor/learners/${r.learnerId}`}>{r.learner}</Link>
                          </span>
                        </td>
                        {r.stages.map((c) => {
                          const [tone, label] = CELL[c.status] || CELL.LOCKED
                          return (
                            <td key={c.key} className="text-center">
                              <span
                                className={`proj-cell proj-cell-${c.overdue ? 'late' : tone}`}
                                title={`${label}${c.overdue ? ', overdue' : ''}${c.score != null ? `, ${c.score}` : ''}`}
                              />
                            </td>
                          )
                        })}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="proj-grid-key mt-3">
                {Object.entries(CELL).map(([k, [tone, label]]) => (
                  <span key={k} className="proj-grid-key-item">
                    <span className={`proj-cell proj-cell-${tone}`} /> {label}
                  </span>
                ))}
                <span className="proj-grid-key-item">
                  <span className="proj-cell proj-cell-late" /> Overdue
                </span>
              </div>
            </>
          )}
        </Card>
      )}

      <p className="small muted mt-4">
        Nobody moves between stages from here. A stage advances only when a mentor approves
        it, which is what makes the grid mean anything.
      </p>
    </Page>
  )
}
