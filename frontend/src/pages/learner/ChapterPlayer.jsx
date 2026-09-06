import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, downloadFile } from '../../api/client'
import { useLearner } from '../../context/LearnerContext'
import { useToast } from '../../context/ToastContext'
import TabBar from '../../components/TabBar'
import SecurePlayer from '../../components/SecurePlayer'
import Materials from '../../components/Materials'
import Icon from '../../components/Icon'
import DoubtBlock from '../../components/DoubtBlock'
import FaqBlock from '../../components/FaqBlock'
import ChapterNotes from '../../components/ChapterNotes'
import FileUpload from '../../components/FileUpload'
import { FormSkeleton } from '../../components/Skeletons'
import { Card, LoadError, Page, StatusTag, Tag, fmtDate } from '../../components/Ui'

/**
 * Four steps per chapter: work through the topics, then test, teach back, task.
 *
 * The first step used to be one video. A chapter now groups several, so it is a list
 * that has to be finished rather than a player that has to end.
 */
const STEPS = [
  ['watch', 'Topics'],
  ['test', 'Test'],
  ['teach', 'Teach back'],
  ['task', 'Task']
]

/** Bytes as something a person reads. */
function kb(n) {
  if (!n) return ''
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${Math.round(n / 1024)} KB`
  return `${Math.round((n / 1024 / 1024) * 10) / 10} MB`
}

export default function ChapterPlayer() {
  const { id } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const { reload, can } = useLearner()
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [step, setStep] = useState('watch')
  const [answers, setAnswers] = useState({})
  const [result, setResult] = useState(null)
  const [explanation, setExplanation] = useState('')
  const [teachResult, setTeachResult] = useState(null)
  const [taskUrl, setTaskUrl] = useState('')
  const [taskFiles, setTaskFiles] = useState([])
  const [playingSession, setPlayingSession] = useState(null)
  const [taskNotes, setTaskNotes] = useState('')
  /* which topic is open in the player; null is the list */
  const [openTopic, setOpenTopic] = useState(null)
  const [topicData, setTopicData] = useState(null)

  /* the same silent skeleton as the learner record: a locked or missing chapter looked
     exactly like a slow one, with no way to tell why or to retry */
  const load = () => {
    setError(null)
    return api.get(`/learner/chapters/${id}`).then(setData).catch((e) => setError(e.message))
  }

  useEffect(() => { load() }, [id])

  /* a failed load leaves data unset, so guard on the shape rather than on truthiness */
  if (error) {
    return (
      <Page title="Chapter">
        <LoadError error={error} onRetry={load} />
      </Page>
    )
  }

  if (!data?.chapter) return <FormSkeleton />

  const { chapter, progress, questions = [], assignment, topics = [], assignmentBrief } = data
  const allWatched = topics.length > 0 && topics.every((t) => t.watched)

  const openTopicPlayer = async (topicId) => {
    setOpenTopic(topicId)
    setTopicData(null)
    try { setTopicData(await api.get(`/learner/topics/${topicId}`)) }
    catch (e) { toast.push(e.message, 'bad'); setOpenTopic(null) }
  }

  /* the player reports real progress; 95 percent counts as watched */
  const onWatchProgress = async (pct) => {
    if (pct < 95 || !openTopic || topicData?.topic?.watched) return
    await api.post(`/learner/topics/${openTopic}/watched`)
    await load()
    setTopicData(await api.get(`/learner/topics/${openTopic}`))
  }

  /* finishing one topic moves to the next, and the last one hands over to the test */
  const afterTopic = async () => {
    const i = topics.findIndex((t) => t.id === openTopic)
    const next = topics[i + 1]
    if (next) { await openTopicPlayer(next.id); return }
    setOpenTopic(null)
    setStep(chapter.hasQuiz ? 'test' : 'task')
  }

  const submitQuiz = async () => {
    try {
      const r = await api.post(`/learner/chapters/${id}/quiz`, answers)
      setResult(r)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const submitTeach = async () => {
    try {
      const r = await api.post(`/learner/chapters/${id}/concept-check`, { explanation })
      setTeachResult(r)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const submitTask = async () => {
    try {
      await api.post(`/learner/chapters/${id}/task`,
        { submissionUrl: taskUrl, fileIds: taskFiles.map((f) => f.id), notes: taskNotes })
      toast.push('Task sent to your mentor.')
      await load()
      await reload()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  return (
    <Page
            title={chapter.title}
      actions={<button className="btn btn-quiet" onClick={() => navigate('/learn')}>Back to roadmap</button>}
    >
      <TabBar
        active={step}
        onChange={setStep}
        items={STEPS
          .filter(([key]) => key !== 'teach' || can('ai_teach_back'))
          .map(([key, label]) => ({
            key,
            label,
            done:
              (key === 'watch' && allWatched) ||
              (key === 'test' && chapter?.testPassed) ||
              (key === 'teach' && progress?.conceptCheckScore != null) ||
              (key === 'task' && progress?.taskStatus === 'APPROVED')
          }))}
      />

      {step === 'watch' && !openTopic && (
        <>
          <Card
            title="Topics in this chapter"
            note={`${topics.length} to work through`}
          >
            {topics.length === 0 ? (
              <div className="locked-note">
                Nothing has been added to this chapter yet. Your mentor will know.
              </div>
            ) : (
              <ol className="topic-list">
                {topics.map((t, i) => (
                  <li key={t.id} className={t.locked ? 'is-locked' : t.watched ? 'is-done' : ''}>
                    <button
                      className="topic-row"
                      disabled={t.locked}
                      onClick={() => openTopicPlayer(t.id)}
                    >
                      <span className="topic-n">{i + 1}</span>
                      <span className="topic-body">
                        <strong>{t.title}</strong>
                        <span className="small muted d-block">
                          {t.resources ? `${t.resources} files` : 'Video'}
                        </span>
                      </span>
                      <span className="topic-state">
                        {t.watched
                          ? <Tag kind="ok">Done</Tag>
                          : t.locked
                            ? <Tag kind="warn">Locked</Tag>
                            : <span className="rec-action">Open</span>}
                      </span>
                    </button>
                  </li>
                ))}
              </ol>
            )}
            {allWatched && (
              <div className="small muted mt-2">
                Every topic done. The test, the teach back and the assignment cover them together.
              </div>
            )}
          </Card>
          <FaqBlock placement="CHAPTER" />
        </>
      )}

      {step === 'watch' && openTopic && (
        <>
          {!topicData ? <FormSkeleton /> : (
            <>
              <Card
                title={topicData.topic.title}
                note={topicData.topic.summary || null}
                actions={
                  <button className="btn btn-quiet" onClick={() => { setOpenTopic(null); setTopicData(null) }}>
                    All topics
                  </button>
                }
              >
                {/* everything the teacher attached, in the order they put it */}
                {topicData.resources?.length > 0 ? (
                  <Materials
                    resources={topicData.resources}
                    codeRunner={topicData.topic.codeRunner}
                    onVideoProgress={onWatchProgress}
                    onVideoEnded={afterTopic}
                  />
                ) : topicData.topic.videoRef ? (
                  <SecurePlayer
                    videoRef={topicData.topic.videoRef}
                    poster={topicData.topic.title}
                    onProgress={onWatchProgress}
                    onEnded={afterTopic}
                  />
                ) : (
                  <div className="locked-note">
                    Nothing has been attached to this topic yet. Your mentor will know.
                  </div>
                )}
                {topicData.topic.watched && (
                  <div className="small muted mt-2">Marked as watched from your playback.</div>
                )}
              </Card>

              {topicData.sessionRecordings?.length > 0 && (
                <Card
                  title="Live sessions on this"
                  note="Recaps and doubt sessions that covered this chapter."
                >
                  {topicData.sessionRecordings.map((r) => (
                    <div className="rec" key={r.id}>
                      <button
                        className="rec-head"
                        onClick={() => setPlayingSession(playingSession === r.id ? null : r.id)}
                      >
                        <span className="rec-icon"><Icon name="recordings" size={16} /></span>
                        <span className="rec-body">
                          <strong>{r.title}</strong>
                          <span className="small muted d-block">
                            {fmtDate(r.heldOn)}{r.durationMin ? ` \u00b7 ${r.durationMin} min` : ''}
                          </span>
                        </span>
                        <span className="rec-action">{playingSession === r.id ? 'Close' : 'Watch'}</span>
                      </button>
                      {playingSession === r.id && (
                        <div className="rec-open">
                          <SecurePlayer videoRef={r.videoRef} poster={r.title} />
                          {r.coveredSummary && <p className="small mt-3 mb-0">{r.coveredSummary}</p>}
                        </div>
                      )}
                    </div>
                  ))}
                </Card>
              )}
              <Card><DoubtBlock chapterId={id} /></Card>
              <Card title="My notes" note="Yours alone. Nobody else reads these.">
                <ChapterNotes chapterId={id} />
              </Card>
            </>
          )}
        </>
      )}

      {step === 'test' && (
        <Card
          title="Chapter test"
          note={chapter?.testPassed
            ? `Passed with ${progress.quizScore}. Pass mark is ${chapter.passMark}.`
            : `Pass mark ${chapter?.passMark}. `
              + `${chapter?.attemptsLeft} of ${chapter?.attemptsAllowed} attempts left, `
              + 'and your best one counts.'}
        >
          {questions.map((q, i) => (
            <div key={q.id} className="mb-3">
              <div className="mb-2" style={{ fontWeight: 600, fontSize: '.92rem' }}>
                <span className="mono me-2" style={{ color: 'var(--ink-30)' }}>Q{i + 1}</span>
                {q.prompt}
              </div>
              {q.options.map((opt, oi) => (
                <label key={oi} className="d-flex gap-2 align-items-start mb-1" style={{ fontSize: '.89rem' }}>
                  <input
                    type="radio"
                    name={q.id}
                    checked={answers[q.id] === oi}
                    onChange={() => setAnswers((a) => ({ ...a, [q.id]: oi }))}
                  />
                  {opt}
                </label>
              ))}
              {result && (
                <div className="mt-1" style={{ fontSize: '.82rem', color: 'var(--ink-60)' }}>
                  {result.review.find((r) => r.id === q.id)?.correct
                    ? <Tag kind="ok">Correct</Tag>
                    : <Tag kind="stop">Not this one</Tag>}
                  {' '}{result.review.find((r) => r.id === q.id)?.explanation}
                </div>
              )}
            </div>
          ))}
          <div className="d-flex gap-2 align-items-center flex-wrap">
            <button
              className="btn btn-pib"
              disabled={chapter?.testPassed || chapter?.attemptsLeft === 0}
              onClick={submitQuiz}
            >
              Submit answers
            </button>
            {result && (
              <>
                <span className="mono">Scored {result.score}</span>
                {result.passed
                  ? <Tag kind="ok">Passed</Tag>
                  : <Tag kind="stop">Below {result.passMark}</Tag>}
                <span className="small muted">{result.note}</span>
              </>
            )}
            {!result && chapter?.attemptsLeft === 0 && !chapter?.testPassed && (
              <span className="small muted">
                No attempts left. Your mentor can reopen this test for you.
              </span>
            )}
          </div>
        </Card>
      )}

      {step === 'teach' && (
        <Card
          title="Teach it back"
          note="Explain this chapter in your own words, as you would to a teammate."
        >
          <textarea
            className="form-control mb-3"
            rows={6}
            value={explanation}
            onChange={(e) => setExplanation(e.target.value)}
            placeholder="Start with what problem this solves, then how it works."
          />
          <div className="d-flex gap-2 align-items-center">
            <button className="btn btn-pib" onClick={submitTeach}>Check my explanation</button>
            {teachResult && (
              <span style={{ fontSize: '.88rem' }}>
                <strong className="mono me-2">{teachResult.score}</strong>{teachResult.verdict}
                {teachResult.gradedBy && teachResult.gradedBy !== 'rubric' && (
                  <span className="tag tag-batch ms-2">graded by {teachResult.gradedBy}</span>
                )}
              </span>
            )}
          </div>
        </Card>
      )}

      {step === 'task' && (
        <Card
          title={assignmentBrief?.title || 'Chapter assignment'}
          note={assignmentBrief?.brief}
          actions={assignment && <StatusTag status={assignment.status} />}
        >
          {assignmentBrief && (
            <div className="small muted mb-3">
              {assignmentBrief.marks} marks
              {assignmentBrief.dueAt && <> &middot; due {fmtDate(assignmentBrief.dueAt)}</>}
              &middot; accepts {assignmentBrief.allow}
              {assignmentBrief.lockNext === false && <> &middot; the next chapter does not wait for this</>}
            </div>
          )}
          {assignment?.mentorFeedback && (
            <div className="locked-note mb-3">
              <strong>From your mentor:</strong> {assignment.mentorFeedback}
            </div>
          )}

          {/*
            * The brief, the dataset and the starter notebook. These have been attached
            * on the chapter and sent to the learner all along; nothing here read the
            * field, so every file a super admin uploaded was invisible and a learner
            * was asked to do the task without the material for it.
            */}
          {assignmentBrief?.files?.length > 0 && (
            <div className="brief-files mb-3">
              <div className="eyebrow mb-2">What you need for this</div>
              <div className="brief-file-row">
                {assignmentBrief.files.map((f) => (
                  <button
                    key={f.id}
                    className="brief-file"
                    disabled={f.missing}
                    title={f.missing ? 'This upload is no longer on the server' : f.filename}
                    onClick={() => downloadFile(f.id).catch((e) => toast.push(e.message, 'bad'))}
                  >
                    <Icon name="import" size={14} />
                    <span className="brief-file-name">{f.filename}</span>
                    {!f.missing && <span className="brief-file-size">{kb(f.sizeBytes)}</span>}
                  </button>
                ))}
              </div>
            </div>
          )}

          <FileUpload
            purpose="TASK"
            label="Attach your notebook, dataset and anything else"
            onAllUploaded={(fs) => setTaskFiles((prev) => [...prev, ...fs])}
          />
          <div className="my-3">
            <label className="form-label">Or a link, if it lives somewhere else</label>
            <input className="form-control" value={taskUrl} onChange={(e) => setTaskUrl(e.target.value)}
              placeholder="Notebook, repo or document link" />
          </div>
          <div className="mb-3">
            <label className="form-label">Notes for your mentor</label>
            <textarea className="form-control" rows={3} value={taskNotes}
              onChange={(e) => setTaskNotes(e.target.value)} />
          </div>
          <button className="btn btn-pib" onClick={submitTask}
            disabled={!taskUrl && taskFiles.length === 0}>
            {assignment ? 'Resubmit' : 'Submit'}
          </button>
        </Card>
      )}
    </Page>
  )
}
