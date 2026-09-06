import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { RoadmapSkeleton } from '../../components/Skeletons'
import { Band, Card, Grid, LoadError, Page, Stat } from '../../components/Ui'

/**
 * Everything the admin runs day to day, with the queue first.
 *
 * The hero is not decoration: it is the list of things that are waiting on a person,
 * and each row goes straight to the screen where the waiting ends.
 */
export default function AdminHome() {
  const [data, setData] = useState(null)
  const toast = useToast()
  const nav = useNavigate()

  const [loadError, setLoadError] = useState(null)

  const load = () => api.get('/admin/overview')
    .then((r) => { setData(r); setLoadError(null) })
    .catch((e) => setLoadError(e.message))
  useEffect(() => { load() }, [])
  if (!data?.learners && data?.learners !== 0) return <RoadmapSkeleton />

  const assignAll = async () => {
    try {
      const r = await api.post('/admin/assign-unassigned', {})
      toast.push(`${r.assigned} learner${r.assigned === 1 ? '' : 's'} assigned round robin.`)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const queue = [
    ['Onboarding', data.onboardingOpen, 'still open', '/admin/onboarding', true],
    ['No mentor', data.unassigned, 'waiting on round robin', '/admin/register', true],
    ['Tasks', data.tasksWaiting, 'submitted, waiting on a mentor', '/mentor/tasks', false],
    ['Projects', data.projectsWaiting, 'submitted, waiting on a mentor', '/mentor/tasks', false],
    ['Mock interviews', data.mocksWaiting, 'requested, not scheduled', '/mentor', false]
  ].filter(([, n]) => n > 0)

  return (
    <Page
      title="Admin overview"
      lede="Everything you run day to day, in one place."
      actions={(
        <>
          <button className="btn" onClick={() => nav('/admin/import')}>Import from the sheet</button>
          <button className="btn btn-pib" onClick={assignAll} disabled={!data.unassigned}>
            Assign {data.unassigned || 0} unassigned
          </button>
        </>
      )}
    >
      <div className="desk-solo">
        <div className="eyebrow">Waiting on somebody</div>
        <h2 style={{ marginTop: 4 }}>
          {queue.length === 0 ? 'Nothing is waiting' : `${queue.length} thing${queue.length > 1 ? 's' : ''} to clear`}
        </h2>
        <div className="desk-list">
          {queue.length === 0 ? (
            <div className="desk-item">
              <span className="desk-kind">Clear</span>
              <span className="desk-title">Every learner is onboarded, assigned and reviewed.</span>
            </div>
          ) : queue.map(([label, n, detail, to, mine]) => (
            <button className="desk-item" key={label} onClick={() => nav(to)}>
              <span className={`desk-kind ${mine ? 'warn' : ''}`}>{label}</span>
              <span className="desk-title mono">{n}</span>
              <span className="desk-detail">{detail}</span>
              <span className={`desk-flag ${mine ? 'late' : ''}`}>{mine ? 'yours' : 'mentor'}</span>
            </button>
          ))}
        </div>
      </div>

      <Grid cols={3} style={{ marginBottom: 16 }}>
        <Stat value={data.learners} label="Learners" icon="batch"
          hint={`${data.premium} premium, ${data.batch} batch`} />
        <Stat value={data.batches} label="Batches" icon="batches" hint="one starts every week" />
        <Stat value={data.mentors} label="Mentors" icon="people"
          hint={data.unassigned ? `${data.unassigned} learners unassigned` : 'every learner covered'} />
        <Stat value={data.onboardingOpen} label="Onboarding open" icon="onboarding"
          hint="gates not cleared yet" />
      </Grid>

      <div className="grid g2">
        <Card title="Where learners are" note="Onboarding gates against the rest">
          <Band segments={[
            ['Onboarding', data.onboardingOpen || 0, '#F0A500'],
            ['Learning', Math.max(0, data.learners - (data.onboardingOpen || 0)), '#00B0F0']
          ]} />
        </Card>

        <Card
          title="Waiting on mentors"
          actions={<button className="btn btn-s" onClick={() => nav('/mentor/tasks')}>Open the queue</button>}
        >
          <table className="table table-pib mb-0">
            <tbody>
              <tr><td>Tasks to review</td><td className="mono text-end">{data.tasksWaiting}</td></tr>
              <tr><td>Projects to review</td><td className="mono text-end">{data.projectsWaiting}</td></tr>
              <tr><td>Mock requests</td><td className="mono text-end">{data.mocksWaiting}</td></tr>
            </tbody>
          </table>
        </Card>
      </div>
    </Page>
  )
}
