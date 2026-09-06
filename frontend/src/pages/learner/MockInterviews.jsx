import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useLearner } from '../../context/LearnerContext'
import { useToast } from '../../context/ToastContext'
import FaqBlock from '../../components/FaqBlock'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, Page, StatusTag, fmtDateTime } from '../../components/Ui'

/**
 * Premium gets a mock automatically after each module. Batch has to have one
 * project approved first, and asks for it. The tile stays visible either way,
 * with the reason shown rather than hidden.
 */
export default function MockInterviews() {
  const { learner, roadmap, loading } = useLearner()
  const toast = useToast()
  const [eligibility, setEligibility] = useState(null)
  const [mocks, setMocks] = useState([])
  const [moduleId, setModuleId] = useState('')

  const load = async () => {
    setEligibility(await api.get('/learner/mock-eligibility'))
    setMocks(await api.get('/learner/mocks'))
  }

  useEffect(() => { load() }, [])

  if (loading || !learner || !eligibility) return <TableSkeleton />

  const request = async () => {
    try {
      await api.post('/learner/mocks', { moduleId })
      toast.push('Requested. Your mentor picks a time from here.')
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  return (
    <Page title="Mock interviews"
      lede="Practice interviews and how each one was scored.">
      <Card
        title={eligibility.eligible ? 'You can take a mock' : 'Not open yet'}
        note={eligibility.reason}
      >
        {eligibility.eligible ? (
          <div className="d-flex gap-2 flex-wrap align-items-center">
            <select className="form-select" style={{ maxWidth: 260 }}
              value={moduleId} onChange={(e) => setModuleId(e.target.value)}>
              <option value="">Whole track</option>
              {(roadmap || []).map((m) => (
                <option key={m.moduleId} value={m.moduleId}>{m.name}</option>
              ))}
            </select>
            <button className="btn btn-pib" onClick={request}>
              {learner.trackType === 'BATCH' ? 'Request a mock' : 'Book a mock'}
            </button>
          </div>
        ) : (
          <div className="locked-note">{eligibility.reason}</div>
        )}
      </Card>

      <Card title="Your mocks">
        {mocks.length === 0 ? (
          <Empty title="No mocks yet">They show up here once one is raised.</Empty>
        ) : (
          <table className="table table-pib mb-0">
            <thead><tr><th>Raised</th><th>Origin</th><th>Status</th><th>Scheduled</th><th>Score</th><th>Feedback</th></tr></thead>
            <tbody>
              {mocks.map((m) => (
                <tr key={m.id}>
                  <td className="mono">{fmtDateTime(m.createdAt)}</td>
                  <td>{m.origin === 'AUTO' ? 'Module complete' : 'Requested'}</td>
                  <td><StatusTag status={m.status} /></td>
                  <td className="mono">{m.scheduledFor ? fmtDateTime(m.scheduledFor) : '\u2014'}</td>
                  <td className="mono">{m.score ?? '\u2014'}</td>
                  <td style={{ maxWidth: 280 }}>{m.feedback || '\u2014'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
      <FaqBlock placement="MOCK" track={learner?.trackType} />
    </Page>
  )
}
