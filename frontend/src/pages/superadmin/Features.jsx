import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Grid, Note, Page, Stat, Switch, Tabs, Tag } from '../../components/Ui'

/**
 * Which parts of the app a learner actually sees, and which course they hold.
 *
 * The premium and batch difference is configuration, not code: a track column here is
 * the whole of it. The course tab is the other half of the same question, since access
 * is granted per course and never per module.
 */
export default function Features() {
  const toast = useToast()
  const [rows, setRows] = useState(null)
  const [courses, setCourses] = useState([])
  const [tab, setTab] = useState(0)

  const load = async () => {
    setRows(await api.get('/super/features'))
    setCourses(await api.get('/super/catalogue').then((t) => t.bundles || []).catch(() => []))
  }
  useEffect(() => { load() }, [])
  if (!rows) return <TableSkeleton />

  /* the switch moves under the finger and rolls back if the server disagrees:
     a toggle that waits on a round trip feels broken even when it works */
  const toggle = async (f, field) => {
    const body = { forPremium: f.forPremium, forBatch: f.forBatch, [field]: !f[field] }
    const before = rows
    setRows((list) => list.map((x) => (x.key === f.key ? { ...x, [field]: !x[field] } : x)))
    try {
      await api.post(`/super/features/${f.key}`, body)
    } catch (e) {
      setRows(before)
      toast.push(e.message, 'bad')
    }
  }

  const premiumOn = rows.filter((f) => f.forPremium).length
  const batchOn = rows.filter((f) => f.forBatch).length
  const live = courses.filter((c) => c.published).length

  return (
    <Page
      title="Features and access"
      lede="Which parts of the app a learner actually sees, and which course they hold."
    >
      <Grid cols={3} style={{ marginBottom: 16 }}>
        <Stat value={rows.length} label="Features" icon="features" hint="each one can differ by track" />
        <Stat value={premiumOn} label="On for premium" icon="batch" />
        <Stat value={batchOn} label="On for batch" icon="batches" />
        <Stat value={live} label="Courses open" icon="library"
          hint={`${courses.length - live} draft`} />
      </Grid>

      <Tabs tabs={['By track', 'By course']} value={tab} onChange={setTab} />

      {tab === 0 && (
        <Card
          title="What each track gets by default"
          note="An admin can still make a per learner exception on the learner record."
        >
          <table className="table table-pib mb-0">
            <thead>
              <tr>
                <th>Feature</th>
                <th style={{ width: 130 }}>Premium</th>
                <th style={{ width: 130 }}>Batch</th>
                <th>Key</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((f) => (
                <tr key={f.key}>
                  <td>{f.label}</td>
                  <td>
                    <Switch checked={f.forPremium} onChange={() => toggle(f, 'forPremium')} />
                  </td>
                  <td>
                    <Switch checked={f.forBatch} onChange={() => toggle(f, 'forBatch')} />
                  </td>
                  <td className="mono tiny" style={{ color: 'var(--ink-30)' }}>{f.key}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      {tab === 1 && (
        <>
          <Note>Access is granted per course, never per module.</Note>
          <Card title="Who can be given each course">
            <table className="table table-pib mb-0">
              <thead>
                <tr><th>Course</th><th>Modules</th><th>Learners</th><th>State</th></tr>
              </thead>
              <tbody>
                {courses.map((c) => (
                  <tr key={c.id}>
                    <td><b>{c.name}</b></td>
                    <td className="tiny muted">{(c.moduleIds || []).length} modules</td>
                    <td className="mono">{c.learners ?? 0}</td>
                    <td>{c.published ? <Tag kind="ok">Open</Tag> : <Tag kind="wait">Draft</Tag>}</td>
                  </tr>
                ))}
                {courses.length === 0 && <tr><td className="tiny muted">No courses yet.</td></tr>}
              </tbody>
            </table>
          </Card>
        </>
      )}
    </Page>
  )
}
