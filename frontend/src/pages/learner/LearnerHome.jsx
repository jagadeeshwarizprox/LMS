import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api/client'
import { useLearner } from '../../context/LearnerContext'
import { useToast } from '../../context/ToastContext'
import GateRail from '../../components/GateRail'
import FaqBlock from '../../components/FaqBlock'
import Robot from '../../components/Robot'
import ThisWeek from '../../components/ThisWeek'
import ContinueWatching from '../../components/ContinueWatching'
import SecurePlayer from '../../components/SecurePlayer'
import { RoadmapSkeleton } from '../../components/Skeletons'
import { Bar, Card, Empty, Grid, Page, Pip, Stat, TrackTag } from '../../components/Ui'

export default function LearnerHome() {
  const { learner, gates, roadmap, stats, prereqVideo, continueWatching, loading, error, reload } = useLearner()
  const navigate = useNavigate()
  const toast = useToast()

  /*
   * Which chapters were locked last time this rendered.
   *
   * Finishing a chapter opens the next one, and that was a state change with no
   * acknowledgement: the row simply stopped being grey while you were looking
   * somewhere else. Comparing against the previous render is the only way to know
   * which row just changed, and the set is seeded on first paint so a fresh sign-in
   * does not animate a roadmap the learner has seen for weeks.
   */
  const wasLocked = useRef(null)
  const [justOpened, setJustOpened] = useState(() => new Set())

  useEffect(() => {
    if (!roadmap?.length) return
    const locked = new Set()
    for (const m of roadmap) for (const ch of (m.chapters || [])) if (ch.locked) locked.add(ch.id)

    if (wasLocked.current === null) { wasLocked.current = locked; return }

    const opened = new Set()
    for (const id of wasLocked.current) if (!locked.has(id)) opened.add(id)
    wasLocked.current = locked

    if (opened.size === 0) return
    setJustOpened(opened)
    const t = setTimeout(() => setJustOpened(new Set()), 900)
    return () => clearTimeout(t)
  }, [roadmap])

  /* modules ease in on the first paint of the roadmap only, never on a refresh */
  const painted = useRef(false)
  const [firstPaint, setFirstPaint] = useState(false)
  useEffect(() => {
    if (!loading && !painted.current) {
      painted.current = true
      setFirstPaint(true)
      const t = setTimeout(() => setFirstPaint(false), 900)
      return () => clearTimeout(t)
    }
  }, [loading])

  if (loading) return <RoadmapSkeleton />
  if (error) return <Page title="We could not load your LMS"><div className="locked-note">{error}</div></Page>

  const watchGuide = async () => {
    await api.post('/learner/guide-video')
    await reload()
  }

  /*
   * The walkthrough plays before anything else, exactly once. It goes through the same
   * grant flow as a chapter, so nothing here carries a video id, and an admin can point
   * it at whatever they like from settings.
   */
  if (!learner.guideVideoWatched) {
    const guide = prereqVideo || {}
    return (
      <Page title={`Welcome, ${learner.name.split(' ')[0]}`} lede="One short walkthrough before anything else opens.">
        <Card
          title={guide.title || 'How to use your LMS'}
          note="It covers the roadmap, tasks and how doubt clearing works."
        >
          {guide.set ? (
            <>
              <SecurePlayer
                videoRef={guide.videoRef}
                poster={guide.title || 'Walkthrough'}
                onProgress={(pct) => { if (pct >= 95) watchGuide() }}
                onEnded={watchGuide}
              />
              <div className="small muted mt-2">
                This opens on its own once you have watched it through.
              </div>
            </>
          ) : (
            <>
              <div className="locked-note mb-3">
                No walkthrough has been set up yet, so this step is open.
              </div>
              <button className="btn btn-pib" onClick={watchGuide}>Continue</button>
            </>
          )}
        </Card>
      </Page>
    )
  }

  const openChapter = (ch, moduleLocked) => {
    if (moduleLocked || ch.locked) {
      toast.push('That chapter is not open yet.', 'bad')
      return
    }
    navigate(`/learn/chapter/${ch.id}`)
  }

  return (
    <Page
      title="My roadmap"
      lede="Work down it in order. A chapter opens once the one before it is done."
      actions={<TrackTag type={learner.trackType} />}
    >
      {!gates.allDone && (
        <Card
          title="Finish onboarding"
          note={gates.enforced
            ? 'Your roadmap is visible now. Chapters open once all three steps are cleared.'
            : 'Your course is already open. These are still worth doing, in your own time.'}
          className="mb-4"
        >
          <GateRail gates={gates} trackType={learner.trackType} />
          <div className="d-flex gap-2 mt-3 flex-wrap">
            {!gates.form && (
              <button className="btn btn-pib" onClick={() => navigate('/learn/intake')}>
                Fill the information form
              </button>
            )}
            {!gates.prereq && (
              <button
                className="btn btn-quiet"
                onClick={async () => { await api.post('/learner/prereq-video'); await reload() }}
              >
                Mark prerequisite video watched
              </button>
            )}
            {!gates.call && learner.trackType === 'PREMIUM' && (
              <button className="btn btn-quiet" onClick={() => navigate('/learn/sessions')}>
                Book your onboarding call
              </button>
            )}
            {!gates.call && learner.trackType === 'BATCH' && (
              <button className="btn btn-quiet" onClick={() => navigate('/learn/batch')}>
                Go to induction
              </button>
            )}
          </div>
        </Card>
      )}

      {/*
        * A reward moment, not decoration.
        *
        * The robot appears when onboarding clears and while there is still a course to
        * finish, then stops appearing once the course is done. Something that turns up
        * on every screen stops being noticed within a day; something that turns up when
        * a thing goes right does not.
        */}
      {gates.allDone && learner.modulesUnlocked && stats.percent < 100 && (
        <div className="zbot-cheer mb-4">
          <Robot size={64} mood={stats.percent > 0 ? 'happy' : 'idle'} />
          <div>
            <b>{stats.percent > 0 ? "You're on your way." : "You're all set."}</b>
            <div className="small muted">
              {stats.percent > 0
                ? `${stats.percent}% of the course watched. Pick up where you left off.`
                : 'Onboarding is done and every chapter is open. Start at the top.'}
            </div>
          </div>
        </div>
      )}

      {learner.modulesUnlocked && <ThisWeek />}
      {learner.modulesUnlocked && <ContinueWatching items={continueWatching} />}
      {!learner.modulesUnlocked && (
        <FaqBlock placement="GATES" track={learner.trackType} title="Why is this locked" />
      )}

      <Grid cols={3} style={{ marginBottom: 18 }}>
        {/* a learner with no course has no denominator, and showing them nought per cent
            told them they had not started when in fact nothing had been given to them */}
        <Stat
          value={stats.hasCourse ? `${stats.percent}%` : '\u2014'}
          label={stats.hasCourse
            ? `Course watched · ${stats.watchedTopics} of ${stats.totalTopics} topics`
            : 'No course assigned yet'}
          icon="recordings"
        />
        <Stat value={stats.avgQuiz} label="Average test score" icon="projects" />
        <Stat value={stats.tasksApproved} label="Tasks approved" icon="check" />
        <Stat value={stats.projectsApproved} label="Projects approved" icon="library" />
      </Grid>

      {roadmap.length === 0 ? (
        <Empty title="No course assigned yet">
          Either your track has not been set on your record, or the course you are on has
          not been published yet. The admin team can sort out both.
        </Empty>
      ) : (
        roadmap.map((m, idx) => (
          <div
            key={m.moduleId}
            className={`module ${m.locked ? 'locked' : ''} ${firstPaint ? 'enter' : ''}`}
            style={firstPaint ? { animationDelay: `${idx * 45}ms` } : undefined}
          >
            <div className="module-head">
              <span className="module-index">{String(idx + 1).padStart(2, '0')}</span>
              <div>
                <div className="module-name">{m.name}</div>
                {m.locked && <div className="module-lock-reason">{m.lockReason}</div>}
              </div>
              <div className="ms-auto"><Bar done={m.done} total={m.total} id={m.moduleId} /></div>
            </div>
            {!m.locked && m.chapters.map((ch) => (
              <button
                key={ch.id}
                className={`chapter-row w-100 text-start border-0 bg-transparent`
                  + `${ch.locked ? ' locked' : ''}${justOpened.has(ch.id) ? ' just-open' : ''}`}
                onClick={() => openChapter(ch, m.locked)}
                disabled={ch.locked}
              >
                <span className="mono chapter-no">
                  {String(ch.position).padStart(2, '0')}
                </span>
                <span>{ch.title}</span>
                <span className="marks">
                  <Pip id={`${ch.id}:w`} state={ch.watched ? 'on' : ''} title="Watched" />
                  <Pip id={`${ch.id}:q`} state={ch.quizScore != null ? 'on' : ''} title="Test" />
                  <Pip id={`${ch.id}:t`} state={ch.conceptCheckScore != null ? 'on' : ''} title="Teach back" />
                  <Pip
                    id={`${ch.id}:a`}
                    state={ch.taskStatus === 'APPROVED' ? 'on' : ch.taskStatus === 'SUBMITTED' ? 'part' : ''}
                    title="Task"
                  />
                </span>
              </button>
            ))}
          </div>
        ))
      )}
    </Page>
  )
}
