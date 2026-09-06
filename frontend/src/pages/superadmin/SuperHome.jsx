import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api/client'
import { TableSkeleton } from '../../components/Skeletons'
import Avatar from '../../components/Avatar'
import {
  Band, Card, Grid, Page, Pct, Ready, Stat, Tag
} from '../../components/Ui'

/**
 * What only the super admin can settle, and how the programme is running underneath it.
 *
 * Everything on this page is either a decision nobody else can take (a role,
 * a feature) or the one number that says whether the machine is healthy. Anything that
 * belongs to the day is on the admin desk instead.
 */
export default function SuperHome() {
  const nav = useNavigate()
  const [state, setState] = useState(null)

  useEffect(() => {
    Promise.all([
      api.get('/admin/overview').catch(() => ({})),
      api.get('/super/catalogue').catch(() => ({ modules: [], bundles: [] })),
      api.get('/super/people').catch(() => []),
      api.get('/super/catalogue/readiness').catch(() => []),
      api.get('/super/activity').catch(() => [])
    ]).then(([overview, tree, people, readiness, activity]) =>
      setState({ overview, tree, people, readiness, activity }))
  }, [])

  if (!state) return <TableSkeleton />

  const { overview, tree, people, readiness, activity } = state
  const modules = tree.modules || []
  const courses = tree.bundles || []
  const mentors = people.filter((p) => p.role === 'MENTOR')
  const activeMentors = mentors.filter((p) => p.active !== false)
  const admins = people.filter((p) => p.role === 'ADMIN')
  const chapters = modules.reduce((a, m) => a + (m.chapters?.length || 0), 0)
  const topics = modules.reduce((a, m) => a + (m.topics || 0), 0)
  const emptyChapters = modules.reduce((a, m) => a + (m.emptyChapters || 0), 0)
  const live = courses.filter((c) => c.published).length
  const learners = overview.learners || 0

  /* the same list the readiness endpoint builds, said in a sentence each */
  const decisions = [
    ...(emptyChapters > 0 ? [[
      `${emptyChapters} chapter${emptyChapters > 1 ? 's have' : ' has'} no topics`,
      'A learner reaching one lands on an empty page',
      '/super/modules'
    ]] : []),
    ...readiness.filter((r) => !r.ready).slice(0, 3).map((r) => [
      `${r.name} is not ready to teach`,
      (r.gaps || []).slice(0, 2).join(' \u00b7 '),
      '/super/courses'
    ]),
    ...(admins.length === 0 ? [[
      'There is no admin account',
      'Nobody can enrol a learner until you create one',
      '/super/people'
    ]] : []),
    ...(overview.unassigned ? [[
      `${overview.unassigned} learner${overview.unassigned > 1 ? 's have' : ' has'} no mentor`,
      'Round robin fills an empty seat, it never reshuffles',
      '/admin/register'
    ]] : [])
  ].slice(0, 5)

  const ready = [
    ['Admin account', admins.length ? `${admins[0].name}, password set` : 'Not created yet', admins.length > 0],
    ['Mentors', `${activeMentors.length} active, ${mentors.length - activeMentors.length} switched off`, activeMentors.length > 0],
    ['Modules', `${modules.length} modules, ${chapters} chapters`, modules.length > 0],
    ['Courses', `${live} live, ${courses.length - live} draft`, live > 0],
    ['Learners', learners ? `${overview.premium || 0} premium, ${overview.batch || 0} batch` : 'Nobody enrolled yet', learners > 0]
  ]

  const stages = [
    ['Onboarding', overview.onboardingOpen || 0, '#F0A500'],
    ['Learning', Math.max(0, learners - (overview.onboardingOpen || 0)), '#00B0F0']
  ]

  return (
    <Page
      title="Overview"
      lede="What needs your decision, and how the whole programme is running."
      actions={(
        <button className="btn" onClick={() => nav('/super/activity')}>Activity log</button>
      )}
    >
      <Ready items={ready} />

      <Grid cols={3} style={{ marginBottom: 16 }}>
        <Stat value={learners} label="Learners" icon="batch"
          hint={`${overview.premium || 0} premium, ${overview.batch || 0} batch`} />
        <Stat value={activeMentors.length} label="Active mentors" icon="people"
          hint={activeMentors.length ? `${Math.round(learners / activeMentors.length)} learners each on average` : 'None yet'} />
        <Stat value={live} label="Live courses" icon="library"
          hint={`${courses.length} in total`} />
        <Stat value={chapters} label="Chapters" icon="catalogue"
          hint={emptyChapters ? `${emptyChapters} still empty` : `${topics} topics`} />
      </Grid>

      <div className="grid g4">
        <div>
          <Card eyebrow="Needs your decision" title="Only you can settle these">
            {decisions.length === 0 ? (
              <div className="tiny muted">Nothing is waiting on you. Everything built, staffed and filled.</div>
            ) : (
              <table className="table-pib">
                <tbody>
                  {decisions.map(([label, detail, to]) => (
                    <tr key={label}>
                      <td>
                        <b>{label}</b>
                        <div className="tiny muted">{detail}</div>
                      </td>
                      <td className="right">
                        <button className="btn btn-s" onClick={() => nav(to)}>Open</button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>

          <Card title="Courses" note="Modules bundled and opened for access">
            <table className="table-pib">
              <thead>
                <tr><th>Course</th><th>Modules</th><th>Learners</th><th>State</th></tr>
              </thead>
              <tbody>
                {courses.map((c) => (
                  <tr key={c.id} onClick={() => nav(`/super/courses/${c.id}`)} style={{ cursor: 'pointer' }}>
                    <td><b>{c.name}</b></td>
                    <td className="tiny muted">{(c.moduleIds || []).length} modules</td>
                    <td className="mono">{c.learners ?? 0}</td>
                    <td>{c.published ? <Tag kind="ok">Live</Tag> : <Tag kind="wait">Draft</Tag>}</td>
                  </tr>
                ))}
                {courses.length === 0 && (
                  <tr><td className="tiny muted">No courses yet.</td></tr>
                )}
              </tbody>
            </table>
          </Card>
        </div>

        <div>
          <Card title="Where learners are" note="Across both tracks">
            <Band segments={stages} />
          </Card>

          <Card
            title="Mentor load"
            note="Learners per active mentor"
            actions={<button className="btn btn-s" onClick={() => nav('/super/people')}>Rebalance</button>}
          >
            <table className="table-pib">
              <tbody>
                {activeMentors.map((m) => {
                  const load = Math.min(100, Math.round(((m.learners || 0) / 16) * 100))
                  return (
                    <tr key={m.id}>
                      <td>
                        <span className="who-cell">
                          <Avatar name={m.name} size={32} />
                          <span>
                            <span className="who-name">{m.name}</span>
                            <span className="who-sub">{m.learners || 0} learners</span>
                          </span>
                        </span>
                      </td>
                      <td className="right" style={{ width: 130 }}><Pct value={load} /></td>
                    </tr>
                  )
                })}
                {activeMentors.length === 0 && (
                  <tr><td className="tiny muted">No mentors yet.</td></tr>
                )}
              </tbody>
            </table>
          </Card>

          <Card
            title="Latest changes"
            actions={<button className="btn btn-s" onClick={() => nav('/super/activity')}>All</button>}
          >
            {activity.slice(0, 6).map((a) => (
              <div className="act" key={a.id}>
                <div className="when">
                  {new Date(a.createdAt).toLocaleString('en-IN', {
                    day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit'
                  })}
                </div>
                <div>
                  <span className="who">{(a.actorEmail || 'system').split('@')[0]}</span>{' '}
                  <span className="tag tag-batch" style={{ margin: '0 6px' }}>{a.entity}</span>
                  {a.detail || a.action}
                </div>
              </div>
            ))}
            {activity.length === 0 && <div className="tiny muted">Nothing recorded yet.</div>}
          </Card>
        </div>
      </div>
    </Page>
  )
}
