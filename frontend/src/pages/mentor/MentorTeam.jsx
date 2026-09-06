import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../../api/client'
import Avatar from '../../components/Avatar'
import Drawer from '../../components/Drawer'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, Loading, Page, Stat, Tag } from '../../components/Ui'

/**
 * For a senior mentor: the people reporting to them, and what each one is carrying.
 *
 * The reporting chain has been on the user record since the beginning and nothing read
 * it, so a lead holding four mentors saw four empty screens and got their numbers by
 * asking. Reading down the tree is not the same as owning the work: a senior can open
 * any learner below them, and scoring and approving stay with the mentor whose name is
 * on the record.
 */
export default function MentorTeam() {
  const [rows, setRows] = useState(null)
  const [error, setError] = useState(null)
  const [open, setOpen] = useState(null)
  const [roster, setRoster] = useState(null)

  const load = () => api.get('/mentor/team')
    .then((r) => { setRows(r); setError(null) })
    .catch((e) => setError(e.message))

  useEffect(() => { load() }, [])

  const openMentor = async (m) => {
    setOpen(m)
    setRoster(null)
    try {
      const all = await api.get('/mentor/roster')
      const flat = all.groups.flatMap((g) => g.learners)
      setRoster(flat.filter((l) => l.mentorId === m.mentorId))
    } catch {
      setRoster([])
    }
  }

  if (error) {
    return (
      <Page title="My team">
        <Empty title="That did not load" icon="risk" action={
          <button className="btn btn-pib" onClick={load}>Try again</button>
        }>{error}</Empty>
      </Page>
    )
  }
  if (!rows) return <TableSkeleton />

  if (rows.length === 0) {
    return (
      <Page title="My team">
        <Empty title="Nobody reports to you">
          When a super admin puts a mentor under you, they and their learners appear here.
        </Empty>
      </Page>
    )
  }

  const total = (k) => rows.reduce((n, r) => n + (r[k] || 0), 0)

  return (
    <Page title="My team"
      lede="Everyone reporting to you, and the shape of what they are carrying.">
      <div className="row g-3 mb-4">
        <div className="col-6 col-lg-3"><Stat value={rows.length} label="Mentors" /></div>
        <div className="col-6 col-lg-3"><Stat value={total('learners')} label="Learners" /></div>
        <div className="col-6 col-lg-3">
          <Stat value={total('atRisk')} label="At risk"
            tone={total('atRisk') ? 'var(--stop)' : undefined} />
        </div>
        <div className="col-6 col-lg-3">
          <Stat value={total('overdueTasks')} label="Overdue tasks"
            tone={total('overdueTasks') ? 'var(--stop)' : undefined} />
        </div>
      </div>

      <Card note="Open a learner to read their record. Reviewing their work stays with their own mentor.">
        <div className="table-responsive">
          <table className="table table-pib mb-0">
            <thead>
              <tr>
                <th>Mentor</th><th>Learners</th><th>At risk</th>
                <th>Overdue</th><th>On hold</th><th />
              </tr>
            </thead>
            <tbody>
              {rows.map((m) => (
                <tr key={m.mentorId} className="row-clickable" onClick={() => openMentor(m)}>
                  <td>
                    <span className="who-cell">
                      <Avatar name={m.name} size={32} />
                      <span>
                        {m.name}
                        <div className="mono small" style={{ color: 'var(--ink-30)' }}>{m.email}</div>
                      </span>
                    </span>
                    {m.reports > 0 && <Tag kind="batch">{m.reports} under them</Tag>}
                  </td>
                  <td className="mono">{m.learners}</td>
                  <td>{m.atRisk > 0 ? <Tag kind="stop">{m.atRisk}</Tag> : <span className="mono">0</span>}</td>
                  <td>{m.overdueTasks > 0 ? <Tag kind="stop">{m.overdueTasks}</Tag> : <span className="mono">0</span>}</td>
                  <td className="mono">{m.onHold}</td>
                  <td className="text-end">
                    <button className="btn btn-quiet btn-sm">See their learners</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <Drawer
        open={Boolean(open)}
        title={open ? `${open.name}'s learners` : ''}
        subtitle="Read only"
        onClose={() => setOpen(null)}
      >
        {roster === null && <Loading label="Reading their roster" />}
        {roster?.length === 0 && <Empty title="No learners on their list" />}
        {roster?.length > 0 && (
          <table className="table table-pib mb-0">
            <thead><tr><th>Learner</th><th>Track</th><th>Progress</th><th /></tr></thead>
            <tbody>
              {roster.map((l) => (
                <tr key={l.learnerId}>
                  <td>
                    <span className="who-cell">
                      <Avatar name={l.name} size={30} track={l.trackType} />
                      <span>{l.name}</span>
                    </span>
                  </td>
                  <td>{l.batch || l.trackType}</td>
                  <td className="mono">{l.percent != null ? `${l.percent}%` : '\u2014'}</td>
                  <td className="text-end">
                    <Link className="btn btn-quiet btn-sm" to={`/mentor/learners/${l.learnerId}`}>
                      Open
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Drawer>
    </Page>
  )
}
