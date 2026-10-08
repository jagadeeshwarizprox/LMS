import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { useDialog } from '../../components/Dialog'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, LoadError, Page, Tabs, fmtDate } from '../../components/Ui'

const TABS = ['Job openings', 'Case studies']
const blankJob = { title: '', company: '', location: '', experience: '', applyUrl: '' }
const blankCase = { title: '', moduleId: '', summary: '', url: '' }
const isUrl = (v) => !v || /^https?:\/\/\S+$/i.test(v.trim())

/**
 * What learners see under Jobs and case studies.
 *
 * Both lists were seeded and had no editor, so the jobs page a learner opened showed
 * the same openings from launch day. Learners only see these when their track has the
 * feature switched on.
 */
export default function JobsAndCases() {
  const toast = useToast()
  const { ask } = useDialog()
  const [tab, setTab] = useState(0)
  const [jobs, setJobs] = useState(null)
  const [cases, setCases] = useState(null)
  const [modules, setModules] = useState([])
  const [job, setJob] = useState(blankJob)
  const [cs, setCs] = useState(blankCase)
  const [error, setError] = useState(null)

  const load = () => {
    setError(null)
    return Promise.all([
      api.get('/super/catalogue/jobs').then(setJobs),
      api.get('/super/catalogue/case-studies').then(setCases)
    ]).catch((e) => setError(e.message))
  }
  useEffect(() => {
    load()
    api.get('/super/catalogue').then((t) => setModules(t.modules || [])).catch(() => setModules([]))
  }, [])

  if (error) return <Page title="Jobs and case studies"><LoadError error={error} onRetry={load} /></Page>
  if (!jobs || !cases) return <TableSkeleton />

  const moduleName = (id) => modules.find((m) => m.id === id)?.name

  const saveJob = async () => {
    if (!isUrl(job.applyUrl)) { toast.push('The apply link should start with https://', 'bad'); return }
    try {
      await api.post('/super/catalogue/jobs', job)
      toast.push(job.id ? 'Opening updated.' : 'Opening posted.')
      setJob(blankJob)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const saveCase = async () => {
    if (!isUrl(cs.url)) { toast.push('The link should start with https://', 'bad'); return }
    try {
      await api.post('/super/catalogue/case-studies', { ...cs, moduleId: cs.moduleId || null })
      toast.push(cs.id ? 'Case study updated.' : 'Case study added.')
      setCs(blankCase)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const remove = async (kind, row) => {
    const ok = await ask({
      title: `Remove "${row.title}"?`,
      intent: 'danger',
      body: 'Learners stop seeing it straight away.',
      confirmLabel: 'Remove'
    })
    if (!ok) return
    try {
      await api.del(`/super/catalogue/${kind}/${row.id}`)
      toast.push('Removed.')
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const setJ = (k) => (e) => setJob((d) => ({ ...d, [k]: e.target.value }))
  const setS = (k) => (e) => setCs((d) => ({ ...d, [k]: e.target.value }))

  return (
    <Page title="Jobs and case studies" lede="What learners see on their Jobs and case studies page.">
      <Tabs tabs={TABS} value={tab} onChange={setTab} />

      {tab === 0 && (
        <>
          <Card title={job.id ? 'Edit opening' : 'Post an opening'}>
            <div className="row g-2">
              <div className="col-md-6">
                <label className="form-label">Role</label>
                <input className="form-control" value={job.title} onChange={setJ('title')} placeholder="Data Analyst" />
              </div>
              <div className="col-md-6">
                <label className="form-label">Company</label>
                <input className="form-control" value={job.company} onChange={setJ('company')} />
              </div>
              <div className="col-md-4">
                <label className="form-label">Location</label>
                <input className="form-control" value={job.location} onChange={setJ('location')} placeholder="Bengaluru, hybrid" />
              </div>
              <div className="col-md-3">
                <label className="form-label">Experience</label>
                <input className="form-control" value={job.experience} onChange={setJ('experience')} placeholder="0 to 2 years" />
              </div>
              <div className="col-md-5">
                <label className="form-label">Apply link</label>
                <input className={`form-control ${isUrl(job.applyUrl) ? '' : 'is-invalid'}`}
                  value={job.applyUrl} onChange={setJ('applyUrl')} placeholder="https://" />
              </div>
              <div className="col-12 d-flex gap-2">
                <button className="btn btn-pib" onClick={saveJob} disabled={!job.title || !job.company}>
                  {job.id ? 'Save changes' : 'Post'}
                </button>
                {job.id && <button className="btn btn-quiet" onClick={() => setJob(blankJob)}>Cancel</button>}
              </div>
            </div>
          </Card>

          <Card title="Posted" note={`${jobs.length} opening${jobs.length === 1 ? '' : 's'}, newest first`}>
            {jobs.length === 0 ? <Empty title="No openings posted" /> : (
              <table className="table table-pib mb-0">
                <thead><tr><th>Role</th><th>Company</th><th>Where</th><th>Posted</th><th /></tr></thead>
                <tbody>
                  {jobs.map((j) => (
                    <tr key={j.id}>
                      <td><strong>{j.title}</strong><div className="small muted">{j.experience}</div></td>
                      <td>{j.company}</td>
                      <td className="small">{j.location}</td>
                      <td className="mono">{j.postedAt ? fmtDate(j.postedAt) : '—'}</td>
                      <td className="text-end">
                        <div className="row-actions">
                          <button className="btn btn-s" onClick={() => setJob({ ...blankJob, ...j })}>Edit</button>
                          <button className="btn btn-s" onClick={() => remove('jobs', j)}>Remove</button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>
        </>
      )}

      {tab === 1 && (
        <>
          <Card title={cs.id ? 'Edit case study' : 'Add a case study'}>
            <div className="row g-2">
              <div className="col-md-6">
                <label className="form-label">Title</label>
                <input className="form-control" value={cs.title} onChange={setS('title')} />
              </div>
              <div className="col-md-6">
                <label className="form-label">Module it goes with</label>
                <select className="form-select" value={cs.moduleId || ''} onChange={setS('moduleId')}>
                  <option value="">Any module</option>
                  {modules.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
                </select>
              </div>
              <div className="col-12">
                <label className="form-label">Summary</label>
                <textarea className="form-control" rows={2} value={cs.summary} onChange={setS('summary')} />
              </div>
              <div className="col-md-8">
                <label className="form-label">Link</label>
                <input className={`form-control ${isUrl(cs.url) ? '' : 'is-invalid'}`}
                  value={cs.url} onChange={setS('url')} placeholder="https://" />
              </div>
              <div className="col-md-4 d-flex align-items-end gap-2">
                <button className="btn btn-pib" onClick={saveCase} disabled={!cs.title}>
                  {cs.id ? 'Save changes' : 'Add'}
                </button>
                {cs.id && <button className="btn btn-quiet" onClick={() => setCs(blankCase)}>Cancel</button>}
              </div>
            </div>
          </Card>

          <Card title="Case studies" note={`${cases.length} in the library`}>
            {cases.length === 0 ? <Empty title="No case studies yet" /> : (
              <table className="table table-pib mb-0">
                <thead><tr><th>Title</th><th>Module</th><th /></tr></thead>
                <tbody>
                  {cases.map((c) => (
                    <tr key={c.id}>
                      <td><strong>{c.title}</strong><div className="small muted">{c.summary}</div></td>
                      <td className="small">{moduleName(c.moduleId) || 'Any'}</td>
                      <td className="text-end">
                        <div className="row-actions">
                          <button className="btn btn-s" onClick={() => setCs({ ...blankCase, ...c })}>Edit</button>
                          <button className="btn btn-s" onClick={() => remove('case-studies', c)}>Remove</button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>
        </>
      )}
    </Page>
  )
}
