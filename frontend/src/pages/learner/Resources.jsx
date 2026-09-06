import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useLearner } from '../../context/LearnerContext'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, Page, fmtDate } from '../../components/Ui'

export default function Resources() {
  const { can } = useLearner()
  const [jobs, setJobs] = useState(null)
  const [cases, setCases] = useState([])

  /* ask only for what this track is entitled to: these endpoints refuse a learner whose
     programme does not include them, and a page that fetches then hides would break */
  useEffect(() => {
    if (can('jobs_referrals')) api.get('/learner/jobs').then(setJobs).catch(() => setJobs([]))
    else setJobs([])
    if (can('case_studies')) api.get('/learner/case-studies').then(setCases).catch(() => setCases([]))
  }, [can])

  if (!jobs) return <TableSkeleton />

  return (
    <Page title="Jobs and case studies"
      lede="Openings, referrals and the case studies from earlier cohorts.">
      {can('jobs_referrals') && (
        <Card title="Openings and referrals">
          {jobs.length === 0 ? <Empty title="Nothing open right now" /> : (
            <table className="table table-pib mb-0">
              <thead><tr><th>Role</th><th>Company</th><th>Location</th><th>Experience</th><th>Posted</th><th /></tr></thead>
              <tbody>
                {jobs.map((j) => (
                  <tr key={j.id}>
                    <td>{j.title}</td>
                    <td>{j.company}</td>
                    <td>{j.location}</td>
                    <td>{j.experience}</td>
                    <td className="mono">{fmtDate(j.postedAt)}</td>
                    <td className="text-end">
                      <a className="btn btn-quiet" href={j.applyUrl} target="_blank" rel="noreferrer">Apply</a>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
      )}

      {can('case_studies') && (
        <Card title="Case studies">
          {cases.length === 0 ? <Empty title="No case studies published yet" /> : (
            <div className="row g-3">
              {cases.map((c) => (
                <div key={c.id} className="col-md-6">
                  <div className="stat h-100">
                    <div style={{ fontFamily: 'Sora', fontWeight: 600, color: 'var(--navy-900)' }}>{c.title}</div>
                    <div className="label mb-2">{c.summary}</div>
                    <a className="btn btn-quiet" href={c.url} target="_blank" rel="noreferrer">Read</a>
                  </div>
                </div>
              ))}
            </div>
          )}
        </Card>
      )}
    </Page>
  )
}
