import { api } from '../../api/client'
import { useLearner } from '../../context/LearnerContext'
import { useToast } from '../../context/ToastContext'
import FaqBlock from '../../components/FaqBlock'
import { RoadmapSkeleton } from '../../components/Skeletons'
import { Card, Empty, Page, Stat, fmtDate } from '../../components/Ui'

/**
 * Everything here is read only. Conversation stays on WhatsApp so it is not split
 * across two places, and the LMS does not try to compete with the group.
 */
export default function BatchSpace() {
  /*
   * rank comes from inside the batch payload, not the top level.
   *
   * It was destructured straight off the context, where no such key exists, so the
   * standing card was guarded by a value that was always undefined and had never once
   * rendered for anybody. It is also null when the leaderboard is switched off for the
   * track, which the same guard handles.
   */
  const { learner, batch, gates = {}, loading, reload } = useLearner()
  const rank = batch?.rank
  const toast = useToast()

  if (loading) return <RoadmapSkeleton />
  if (!learner) return null

  if (learner.trackType !== 'BATCH') {
    return (
      <Page title="My batch"
      lede="Your cohort, its schedule and where the group talks.">
        <Empty title="You are on the premium track">
          Premium learners get individual onboarding and one to one sessions rather than a cohort.
        </Empty>
      </Page>
    )
  }

  if (!batch?.code) return <Empty title="No batch assigned yet">The admin team assigns your batch before it starts.</Empty>

  const watchedInduction = async () => {
    await api.post('/learner/induction-watched')
    toast.push('Induction cleared. Your modules are opening.')
    await reload()
  }

  return (
    <Page title={batch.name} lede={`Batch ${batch.code}. Everything your cohort shares.`}>
      {!gates.induction && (
        <Card title="Induction" note="Modules stay closed until the induction is done, so nobody runs ahead of the cohort.">
          {batch.inductionDone ? (
            <>
              <div className="video-frame mb-3">Recorded induction plays here</div>
              <button className="btn btn-pib" onClick={watchedInduction}>I have watched the induction</button>
            </>
          ) : (
            <div className="locked-note">
              Live induction on {fmtDate(batch.inductionDate)}. Join from the WhatsApp group.
            </div>
          )}
        </Card>
      )}

      {rank && (
        <div className="row g-3 mb-4">
          <div className="col-6 col-lg-3">
            <Stat value={rank.inBatch ? `#${rank.inBatch}` : '\u2014'} label="In your batch"
              hint={`of ${rank.batchSize}`} icon="batch" />
          </div>
          <div className="col-6 col-lg-3">
            <Stat value={rank.overall ? `#${rank.overall}` : '\u2014'} label="On your course"
              hint={`of ${rank.overallSize}`} icon="roadmap" />
          </div>
        </div>
      )}

      {/* cohort pace is a track setting: with it off the server sends nothing and the
          learner sees their own progress without being measured against the room */}
      <div className="row g-3 mb-4">
        <div className="col-6 col-lg-3"><Stat value={batch.size} label="Learners in this batch" /></div>
        {batch.cohortPace && (
          <>
            <div className="col-6 col-lg-3"><Stat value={`${batch.cohortPace.mine ?? 0}%`} label="Your progress" /></div>
            <div className="col-6 col-lg-3"><Stat value={`${batch.cohortPace.cohortAverage ?? 0}%`} label="Cohort average" /></div>
            <div className="col-6 col-lg-3">
              <Stat
                value={batch.cohortPace.standing === 'AHEAD' ? 'On pace' : 'Behind'}
                label="Where you stand"
                tone={batch.cohortPace.standing === 'AHEAD' ? 'var(--teal-600)' : 'var(--warn)'}
              />
            </div>
          </>
        )}
      </div>

      <div className="row g-3">
        <div className="col-lg-7">
          <Card title="Announcements" note="Posted by your mentor. Replies happen in the WhatsApp group.">
            {(batch.announcements || []).length === 0
              ? <Empty title="Nothing posted yet" />
              : batch.announcements.map((a) => (
                  <div key={a.id} className="mb-3 pb-3" style={{ borderBottom: '1px solid var(--line)' }}>
                    <div className="eyebrow mb-1">{a.authorName} \u00b7 {fmtDate(a.createdAt)}</div>
                    <div style={{ fontSize: '.9rem' }}>{a.body}</div>
                  </div>
                ))}
            {batch.whatsappLink && (
              <a className="btn btn-navy" href={batch.whatsappLink} target="_blank" rel="noreferrer">
                Open the batch WhatsApp group
              </a>
            )}
          </Card>

          {(batch.leaderboard || []).length > 0 && (
          <Card title="Leaderboard">
            <table className="table table-pib mb-0">
              <thead><tr><th>#</th><th>Learner</th><th>Progress</th><th>Avg test</th><th>Tasks</th></tr></thead>
              <tbody>
                {(batch.leaderboard || []).map((r, i) => (
                  <tr key={r.name}>
                    <td className="mono">{i + 1}</td>
                    <td>{r.name}</td>
                    <td className="mono">{r.percent}%</td>
                    <td className="mono">{r.avgQuiz}</td>
                    <td className="mono">{r.tasksApproved}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
          )}
        </div>

        <div className="col-lg-5">
          <Card title="Who is in this batch">
            <div style={{ columns: 2, fontSize: '.87rem', lineHeight: 1.9 }}>
              {(batch.roster || []).map((n) => <div key={n}>{n}</div>)}
            </div>
          </Card>
        </div>
      </div>
      <FaqBlock placement="BATCH" track={learner?.trackType} />
    </Page>
  )
}
