import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../../api/client'
import Avatar from '../../components/Avatar'
import { useDialog } from '../../components/Dialog'
import { TableSkeleton } from '../../components/Skeletons'
import { useToast } from '../../context/ToastContext'
import { Card, Empty, LoadError, Page, Stat, Tag, TrackTag } from '../../components/Ui'

/**
 * Stages one and two of the lifecycle, made visible. The learner sees three gates;
 * this is the other half, the part the team owns, with the one number that matters:
 * how long somebody has been sitting at the same step.
 */
export default function OnboardingBoard() {
  const toast = useToast()
  const { ask } = useDialog()
  const [data, setData] = useState(null)
  const [only, setOnly] = useState('')

  const [loadError, setLoadError] = useState(null)

  const load = () => api.get('/admin/onboarding-board')
    .then((r) => { setData(r); setLoadError(null) })
    .catch((e) => setLoadError(e.message))
  useEffect(() => { load() }, [])

  /*
   * Group link set is one of the seven gates this board counts, and it was the only
   * one with nowhere to clear it: the route existed and nothing called it, so a
   * learner sat at that step until somebody edited the record by hand.
   */
  const setGroupLink = async (r) => {
    const answer = await ask({
      title: `WhatsApp group link for ${r.name}`,
      body: 'The conversation lives on WhatsApp, so this is the link the learner is given '
          + 'once their group exists. Setting it clears that gate on this board.',
      fields: [{
        name: 'link', label: 'Group link', required: true,
        placeholder: 'https://chat.whatsapp.com/...'
      }],
      confirmLabel: 'Save'
    })
    if (!answer) return
    try {
      await api.post(`/admin/learners/${r.learnerId}/group-link`, { link: answer.link.trim() })
      toast.push(`Group link set for ${r.name}.`)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  if (loadError) {
    return <Page title="Onboarding board"><LoadError error={loadError} onRetry={load} /></Page>
  }
  if (!data) return <TableSkeleton />

  const rows = data.rows.filter((r) => !only || r.stuckOn === only)

  return (
    <Page
      title="Onboarding board"
      lede="Every learner who has not cleared their gates yet, and what is holding each one up."
      actions={data.overdue > 0 ? <Tag kind="stop">{data.overdue} over a week</Tag> : null}
    >
      <div className="row g-3 mb-4">
        <div className="col-6 col-lg-3"><Stat value={data.total} label="In onboarding" /></div>
        <div className="col-6 col-lg-3">
          <Stat value={data.overdue} label="Waiting over a week"
            tone={data.overdue ? 'var(--stop)' : undefined} />
        </div>
        <div className="col-6 col-lg-6">
          <div className="stat h-100">
            <div className="eyebrow mb-2">Where they are stuck</div>
            <div className="funnel">
              {data.funnel.filter((f) => f.waiting > 0).map((f) => (
                <button
                  key={f.step}
                  className={`funnel-step ${only === f.step ? 'on' : ''}`}
                  onClick={() => setOnly(only === f.step ? '' : f.step)}
                >
                  <span className="mono">{f.waiting}</span> {f.step}
                </button>
              ))}
              {data.funnel.every((f) => f.waiting === 0) && (
                <span className="small muted">Nobody is stuck.</span>
              )}
            </div>
          </div>
        </div>
      </div>

      <Card note="A learner still in onboarding after a week is somebody to call, not somebody to email again.">
        {rows.length === 0 ? (
          <Empty title="Nobody in onboarding">Everyone provisioned has their modules open.</Empty>
        ) : (
          <div className="table-responsive">
            <table className="table table-pib mb-0">
              <thead>
                <tr>
                  <th>Learner</th><th>Track</th><th>Progress through onboarding</th>
                  <th>Stuck on</th><th>Waiting</th><th />
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.learnerId}>
                    <td>
                      <span className="who-cell">
                        <Avatar name={r.name} size={34} track={r.trackType} />
                        <span>
                          <Link to={`/mentor/learners/${r.learnerId}`}>{r.name}</Link>
                          <div className="mono small" style={{ color: 'var(--ink-30)' }}>{r.email}</div>
                        </span>
                      </span>
                    </td>
                    <td>
                      <TrackTag type={r.trackType} />
                      {r.batch && <div className="mono small mt-1">{r.batch}</div>}
                    </td>
                    <td>
                      <div className="steps">
                        {r.steps.map((s) => (
                          <span key={s.label} className={`step-dot ${s.done ? 'done' : ''}`} title={s.label} />
                        ))}
                      </div>
                      <div className="small muted mt-1">
                        {r.formSections}/8 form sections
                      </div>
                    </td>
                    <td>
                      {r.onHold
                        ? <Tag kind="batch">On hold</Tag>
                        : <span className="small">{r.stuckOn}</span>}
                    </td>
                    <td className="mono">
                      {r.overdue
                        ? <Tag kind="stop">{r.daysWaiting} days</Tag>
                        : `${r.daysWaiting} days`}
                    </td>
                    <td className="text-end">
                      <button
                        className="btn btn-quiet btn-sm"
                        onClick={() => setGroupLink(r)}
                      >
                        {r.steps.find((s) => s.label === 'Group link set')?.done
                          ? 'Change group link'
                          : 'Set group link'}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

    </Page>
  )
}
