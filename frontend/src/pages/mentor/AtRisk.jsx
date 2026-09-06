import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../../api/client'
import { TableSkeleton } from '../../components/Skeletons'
import { useToast } from '../../context/ToastContext'
import { useDialog } from '../../components/Dialog'
import Avatar from '../../components/Avatar'
import { Card, Empty, Page, Tag } from '../../components/Ui'

/**
 * Biweekly calls do not scale to seventy learners a batch, so drift is read from
 * activity the LMS already holds and shown as a short list to act on.
 */
export default function AtRisk() {
  const [rows, setRows] = useState(null)
  const toast = useToast()
  const { ask } = useDialog()
  useEffect(() => { api.get('/mentor/at-risk').then(setRows) }, [])

  /**
   * At risk used to name a problem and offer a WhatsApp link, so the follow up left
   * no trace. A nudge from here is drafted against the actual reason, editable, and
   * kept on the learner's record.
   */
  const nudge = async (r) => {
    let draft = ''
    try {
      const d = await api.get(
        `/mentor/learners/${r.learnerId}/nudge-draft?reasons=${encodeURIComponent(r.reasons.join('|'))}`)
      draft = d.body
    } catch { draft = '' }
    const out = await ask({
      title: `Nudge ${r.name}`,
      body: 'Named after what is actually stalled, rather than asking whether everything is alright, '
          + 'which nobody answers. Edit it freely.',
      fields: [
        { name: 'subject', label: 'Subject', required: true, value: 'Checking in' },
        { name: 'body', label: 'Message', multiline: true, required: true, value: draft }
      ],
      confirmLabel: 'Send'
    })
    if (!out) return
    try {
      await api.post(`/mentor/learners/${r.learnerId}/message`,
        { subject: out.subject, body: out.body, kind: 'NUDGE' })
      toast.push('Sent, and kept on their record.')
    } catch (e) { toast.push(e.message, 'bad') }
  }

  if (!rows) return <TableSkeleton />

  return (
    <Page title="At risk"
      lede="Learners drifting on login, submissions or attendance.">
      <Card note="Triggers: no sign in for seven days, an overdue task, or onboarding still open a week after joining.">
        {rows.length === 0 ? (
          <Empty title="Nobody is drifting">Everyone assigned to you is active and on track.</Empty>
        ) : (
          <table className="table table-pib mb-0">
            <thead><tr><th>Learner</th><th>Track</th><th>Batch</th><th>Why</th><th>Contact</th></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.learnerId}>
                  <td>
                    <span className="who-cell">
                      <Avatar name={r.name} size={32} track={r.trackType} />
                      <Link to={`/mentor/learners/${r.learnerId}`}>{r.name}</Link>
                    </span>
                  </td>
                  <td>{r.trackType === 'PREMIUM' ? 'Premium' : 'Batch'}</td>
                  <td className="mono">{r.batch || '\u2014'}</td>
                  <td>
                    <div className="d-flex flex-wrap gap-1">
                      {r.reasons.map((x) => <Tag key={x} kind="stop">{x}</Tag>)}
                    </div>
                  </td>
                  <td className="text-end">
                    <button className="btn btn-pib" onClick={() => nudge(r)}>Nudge</button>
                    {r.phone && (
                      <a className="btn btn-quiet ms-2"
                        href={`https://wa.me/${String(r.phone).replace(/[^0-9]/g, '')}`}
                        target="_blank" rel="noreferrer">WhatsApp</a>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </Page>
  )
}
