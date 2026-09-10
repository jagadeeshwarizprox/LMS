import { useEffect, useState } from 'react'
import { useAuth } from '../../context/AuthContext'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { useDialog } from '../../components/Dialog'
import Icon from '../../components/Icon'
import Drawer from '../../components/Drawer'
import FileUpload from '../../components/FileUpload'
import ResourceList from '../../components/ResourceList'
import VideoField, { parseVideo } from '../../components/VideoField'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, Page, Ring, Switch, Tag } from '../../components/Ui'

/**
 * One chapter: the topics to work through, then the three things assessed once for all
 * of them.
 *
 * They are on one screen because they are one decision. Whether a chapter needs a test
 * depends on what its topics turned out to cover, and splitting that across tabs is how
 * a chapter ends up with a test switched on and no questions in it.
 */
export default function ChapterDetail() {
  const { id } = useParams()
  const nav = useNavigate()
  const toast = useToast()
  const { ask } = useDialog()
  const { user } = useAuth()
  /* who may rewrite the shared chapter, as opposed to setting the work on it */
  const authoring = user?.role === 'ADMIN' || user?.role === 'SUPER_ADMIN' 

  const [tree, setTree] = useState(null)
  const [questions, setQuestions] = useState([])
  const [rubrics, setRubrics] = useState([])

  /* the rubric list, so a chapter can name one to be marked against */
  useEffect(() => {
    api.get('/super/catalogue/rubrics').then(setRubrics).catch(() => setRubrics([]))
  }, [])
  const [editing, setEditing] = useState(null)
  const [topicAt, setTopicAt] = useState(0)

  const load = async () => {
    const t = await api.get('/super/catalogue')
    setTree(t)
    try { setQuestions(await api.get(`/super/chapters/${id}/questions`)) } catch { setQuestions([]) }
  }
  useEffect(() => { load() }, [id])
  if (!tree) return <TableSkeleton />

  let m = null, c = null
  for (const mod of tree.modules) {
    const hit = mod.chapters.find((x) => x.id === id)
    if (hit) { m = mod; c = hit; break }
  }
  if (!c) return <Empty title="Chapter not found">It may have been deleted.</Empty>

  const run = async (fn, msg) => {
    try { await fn(); toast.push(msg); await load() }
    catch (e) { toast.push(e.message, 'bad') }
  }

  /* ------------------------------------------------------------------ topics */

  const pasteTopics = async () => {
    const r = await ask({
      title: 'Paste an outline',
      body: 'One topic per line, with an optional duration after a pipe: Variables and types | 18',
      fields: [{ name: 'text', label: 'Outline', multiline: true, required: true }],
      confirmLabel: 'Add them'
    })
    if (!r) return
    try {
      const out = await api.post(`/super/catalogue/chapters/${id}/topics/bulk`, { text: r.text })
      toast.push(`${out.added} added, ${out.skipped} already there.`)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const moveTopic = (topicId, delta) => {
    const ids = c.topics.map((t) => t.id)
    const i = ids.indexOf(topicId)
    const j = i + delta
    if (j < 0 || j >= ids.length) return
    ids.splice(j, 0, ids.splice(i, 1)[0])
    run(() => api.post(`/super/catalogue/chapters/${id}/topics/reorder`, { ids }), 'Order saved.')
  }

  const removeTopic = async (t) => {
    const ok = await ask({
      title: `Delete ${t.title}?`,
      body: 'A topic anyone has watched is archived instead, and stays in their record.',
      confirmLabel: 'Delete',
      intent: 'danger'
    })
    if (!ok) return
    try {
      const r = await api.del(`/super/catalogue/topics/${t.id}`)
      toast.push(r.reason)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  /* ------------------------------------------------------------- the blocks */

  const saveTest = (patch) =>
    run(() => api.put(`/super/catalogue/chapters/${id}/test`, { ...c.test, ...patch }), 'Test saved.')

  const saveAssignment = (patch) =>
    run(() => api.put(`/super/catalogue/chapters/${id}/assignment`,
      { ...c.assignment, ...patch }), 'Assignment saved.')

  const saveTeachback = (patch) =>
    run(() => api.put(`/super/catalogue/chapters/${id}/teachback`,
      { ...c.teachback, ...patch }), 'Teach back saved.')

  /**
   * Writing a question, and editing one that already exists.
   *
   * Three things were wrong with this. The correct option was pre-filled with A and not
   * required, so a question where nobody touched that box saved silently with the first
   * option marked right. The explanation was optional, so a learner could be told they
   * were wrong and nothing else. And once a question was saved there was no way back into
   * it at all: the list rendered as plain text with no edit and no delete, so a typo in a
   * published test could only be fixed in the database.
   *
   * The same form now does both. Opening a model-drafted question in it and saving is
   * what reviews it, which is the review step the Draft tag has always been promising.
   */
  const questionForm = async (existing) => {
    const opts = existing?.options || []
    const letters = ['A', 'B', 'C', 'D']
    const r = await ask({
      title: existing ? 'Edit the question' : 'Write a question',
      body: existing?.draft
        ? 'The model drafted this one. Saving it is what marks it reviewed and lets '
          + 'learners see it.'
        : undefined,
      fields: [
        { name: 'prompt', label: 'Question', required: true, multiline: true,
          value: existing?.prompt || '' },
        { name: 'a', label: 'Option A', required: true, value: opts[0] || '' },
        { name: 'b', label: 'Option B', required: true, value: opts[1] || '' },
        { name: 'c', label: 'Option C', value: opts[2] || '' },
        { name: 'd', label: 'Option D', value: opts[3] || '' },
        /* no default: the box has to be answered rather than accepted */
        { name: 'correct', label: 'Correct option', required: true,
          value: existing ? letters[existing.correctIndex] || '' : '',
          placeholder: 'Which one is right?',
          options: letters.map((x) => ({ value: x, label: `Option ${x}` })) },
        { name: 'explanation', label: 'Why', required: true, multiline: true,
          value: existing?.explanation || '',
          placeholder: 'Shown after they answer. This is the only teaching the test does.' },
        { name: 'marks', label: 'Marks', type: 'number', min: 1,
          value: String(existing?.marks || 1),
          hint: 'What this question is worth against the pass mark. Leave at one if every '
              + 'question in the test counts the same.' }
      ],
      confirmLabel: existing ? 'Save the question' : 'Add question'
    })
    if (!r) return
    const options = [r.a, r.b, r.c, r.d].filter((x) => x && x.trim())
    const correctIndex = letters.indexOf(r.correct)
    if (correctIndex < 0) { toast.push('Mark which option is correct.', 'bad'); return }
    if (correctIndex >= options.length) { toast.push('That option is empty.', 'bad'); return }
    run(() => api.post('/super/catalogue/questions', {
      id: existing?.id || null,
      chapterId: id,
      prompt: r.prompt,
      options,
      correctIndex,
      explanation: r.explanation,
      marks: Math.max(1, Number(r.marks) || 1)
    }), existing ? 'Question saved.' : 'Question added.')
  }

  const addQuestion = () => questionForm(null)

  const removeQuestion = async (q) => {
    const ok = await ask({
      title: 'Delete this question?',
      body: q.prompt,
      intent: 'danger',
      confirmLabel: 'Delete'
    })
    if (!ok) return
    run(() => api.del(`/super/catalogue/questions/${q.id}`), 'Question deleted.')
  }

  /*
   * The model writes five questions from the chapter's own topics and marks every one of
   * them a draft. Nothing drafted is ever served to a learner: the test only ever contains
   * questions a person has opened and saved. The button said "Draft five" and explained
   * none of that, so nobody knew what pressing it would do.
   */
  const draft = async () => {
    const ok = await ask({
      title: 'Draft five questions from this chapter?',
      body: 'The model reads the topics in this chapter and writes five multiple choice '
          + 'questions from them. They arrive marked as drafts and no learner sees a draft. '
          + 'Open each one, correct it and save it, and that is what puts it into the test.',
      confirmLabel: 'Draft five'
    })
    if (!ok) return
    try {
      await api.post(`/super/chapters/${id}/draft-quiz`, { count: 5 })
      toast.push('Five drafted. Open each one to review it before learners see it.')
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const asg = c.assignment
  const topic = c.topics[Math.min(topicAt, Math.max(0, c.topics.length - 1))] || null
  const published = questions.filter((q) => !q.draft).length

  return (
    <Page
      title={c.title}
      lede={`${m.name} \u00b7 chapter ${c.position} of ${m.chapters.length}`}
      actions={(
        <>
          <button className="btn" onClick={() => nav('/super/modules')}>Back to modules</button>
          <button className="btn" onClick={pasteTopics}>Paste an outline</button>
          <button className="btn btn-pib" onClick={() => setEditing({ chapterId: id })}>Add a topic</button>
        </>
      )}
    >
      <div className="ws">
        {/* ------------------------------------------------ topics in this chapter */}
        <div className="wsc ws-l">
          <div className="wsl">Topics in this chapter</div>
          {c.topics.map((t, i) => (
            <button
              key={t.id}
              className={`tbtn ${topicAt === i ? 'on' : ''}`}
              onClick={() => setTopicAt(i)}
            >
              <span className="n">{i + 1}</span>
              <span>{t.title}</span>
            </button>
          ))}
          <button
            className="tbtn"
            style={{ color: 'var(--ink-30)' }}
            onClick={() => setEditing({ chapterId: id })}
          >
            <span className="n">+</span><span>Add a topic</span>
          </button>

          <div className="wsl" style={{ marginTop: 18 }}>Chapter order</div>
          <div className="chips">
            {m.chapters.map((ch, j) => (
              <button
                key={ch.id}
                className={`chip ${ch.id === c.id ? 'on' : ''}`}
                onClick={() => nav(`/super/chapters/${ch.id}`)}
                title={ch.title}
              >
                C{j + 1}
              </button>
            ))}
          </div>
        </div>

        {/* ------------------------------------------------- the selected topic */}
        <div className="wsc">
          {!topic ? (
            <Empty title="No topics in this chapter" icon="catalogue"
              action={<button className="btn btn-pib" onClick={pasteTopics}>Paste an outline</button>}>
              A chapter with no topics opens as an empty page, and blocks the course from publishing.
            </Empty>
          ) : (
            <>
              <div className="wsl">Topic {topicAt + 1} &middot; video and files</div>
              <div className="d-flex align-items-start gap-2 mb-3 flex-wrap">
                <div>
                  <h3>{topic.title}</h3>
                  {topic.summary && <div className="tiny muted">{topic.summary}</div>}
                </div>
                <div className="ms-auto d-flex gap-2">
                  <button className="icon-btn" aria-label="Move up" onClick={() => moveTopic(topic.id, -1)}>
                    <Icon name="up" size={15} />
                  </button>
                  <button className="icon-btn" aria-label="Move down" onClick={() => moveTopic(topic.id, 1)}>
                    <Icon name="down" size={15} />
                  </button>
                  <button className="btn btn-s" onClick={() => setEditing({ ...topic, chapterId: id })}>
                    Edit
                  </button>
                  <button className="btn btn-s btn-x" onClick={() => removeTopic(topic)}>Remove</button>
                </div>
              </div>

              <div className="vbox" onClick={() => setEditing({ ...topic, chapterId: id })}>
                {topic.hasVideo
                  ? <div className="play" />
                  : <div className="vlab">No video on this topic yet</div>}
              </div>

              <div className="d-flex gap-2 align-items-center flex-wrap mb-3">
                {topic.hasVideo
                  ? <Tag kind="ok">
                      {topic.videoProvider === 'CLOUDFLARE_STREAM' ? 'Stream' : 'YouTube'} {topic.videoMasked}
                    </Tag>
                  : <Tag kind="wait">No video</Tag>}
                <span className="tiny muted">{topic.resources} files</span>
                {topic.codeRunner !== 'NONE' && <Tag kind="batch">{topic.codeRunner} runner</Tag>}
              </div>

              <div className="wsl">Files learners can open</div>
              <ResourceList topicId={topic.id} onChanged={load} />
            </>
          )}
        </div>

        {/* ----------------------------------- what is assessed once per chapter */}
        <div className="wsc ws-r">
          <div className="wsl">Chapter level &middot; covers every topic</div>

          <a className="asset" href="#chapter-test">
            <div className="ico">TEST</div>
            <div>
              <b>Test</b>
              <span>{c.test.enabled
                ? (questions.length ? `${questions.length} questions` : 'Switched on, nothing written')
                : 'Off for this chapter'}</span>
            </div>
            <span className="go">{questions.length ? 'Edit' : 'Build'}</span>
          </a>

          <a className="asset" href="#chapter-teachback">
            <div className="ico k2">TB</div>
            <div>
              <b>Teach back</b>
              <span>{c.teachback.enabled
                ? (c.teachback.modelValidates ? 'Checked by the model' : 'Checked against a rubric')
                : 'Off for this chapter'}</span>
            </div>
            <span className="go">{c.teachback.enabled ? 'Edit' : 'Turn on'}</span>
          </a>

          <a className="asset" href="#chapter-assignment">
            <div className="ico k3">TASK</div>
            <div>
              <b>Assignment</b>
              <span>{asg.enabled ? 'Upload, mentor reviews' : 'None'}</span>
            </div>
            <span className="go">{asg.enabled ? 'Edit' : 'Add'}</span>
          </a>

          <div className="wsl" style={{ marginTop: 18 }}>Rules for this chapter</div>
          <div style={{ display: 'grid', gap: 9 }}>
            <Switch
              checked={c.test.enabled}
              label="Test must be passed to move on"
              onChange={(v) => saveTest({ enabled: v })}
            />
            <Switch
              checked={asg.lockNext}
              label="Assignment must be approved to move on"
              onChange={(v) => saveAssignment({ lockNext: v })}
            />
            <Switch
              checked={c.active}
              label="Show this chapter in the roadmap"
              onChange={(v) => run(
                () => api.post('/super/catalogue/chapters', { ...c, topics: undefined, active: v }),
                v ? 'Shown.' : 'Hidden.')}
            />
          </div>

          <div className="wsl" style={{ marginTop: 18 }}>How this chapter stands</div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap' }}>
            <Ring
              pct={questions.length ? Math.round((published / questions.length) * 100) : 0}
              size={74}
              label="reviewed"
            />
            <div className="tiny muted">
              {questions.length
                ? `${published} of ${questions.length} questions reviewed. ${c.studied || 0} learners have worked through it.`
                : 'No test yet, so nothing to report.'}
            </div>
          </div>
        </div>
      </div>

      {/* --------------------------------------------------------- the editors */}
      <div id="chapter-assignment" />
      <Card
        title="Assignment"
        note="One upload for the whole chapter, reviewed by the mentor"
        actions={(
          <Switch
            checked={asg.enabled}
            label={asg.enabled ? 'On' : 'Off'}
            onChange={(v) => saveAssignment({ enabled: v })}
          />
        )}
      >
        <div className="row g-3">
          <div className="col-lg-6">
            <label className="mb-3">
              <span className="form-label">Title</span>
              <input className="form-control" defaultValue={asg.title || ''}
                onBlur={(e) => e.target.value !== asg.title && saveAssignment({ title: e.target.value })} />
            </label>
            <label className="mb-3">
              <span className="form-label">Brief for the learner</span>
              <textarea className="form-control" rows={4} defaultValue={asg.brief || ''}
                onBlur={(e) => e.target.value !== asg.brief && saveAssignment({ brief: e.target.value })} />
            </label>
            <div className="row g-3">
              <label className="mb-3 col-6">
                <span className="form-label">Due in days</span>
                <input className="form-control" type="number" defaultValue={asg.dueDays}
                  onBlur={(e) => saveAssignment({ dueDays: Number(e.target.value) || 7 })} />
              </label>
              <label className="mb-3 col-6">
                <span className="form-label">Marks</span>
                <input className="form-control" type="number" defaultValue={asg.marks}
                  onBlur={(e) => saveAssignment({ marks: Number(e.target.value) || 20 })} />
              </label>
            </div>
            <label className="mb-3">
              <span className="form-label">Accepted file types</span>
              <input className="form-control" defaultValue={asg.allow || ''}
                onBlur={(e) => saveAssignment({ allow: e.target.value })} />
              <span className="tiny muted">Enforced on submit, not just shown.</span>
            </label>
            {/*
              * A rubric on the chapter, so the repeatable work is the half that gets
              * marked consistently. Only ad-hoc assignments could carry one before,
              * which was exactly backwards.
              */}
            <label className="mb-3">
              <span className="form-label">Mark against a rubric</span>
              <select className="form-select" value={asg.rubricId || ''}
                onChange={(e) => saveAssignment({ rubricId: e.target.value || null })}>
                <option value="">No rubric, a single score</option>
                {rubrics.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
              </select>
            </label>
          </div>
          <div className="col-lg-6">
            <div style={{ display: 'grid', gap: 10, marginBottom: 14 }}>
              <Switch
                checked={asg.review}
                label="Mentor review required"
                onChange={(v) => saveAssignment({ review: v })}
              />
              <Switch
                checked={asg.resubmit}
                label="Allow resubmission"
                onChange={(v) => saveAssignment({ resubmit: v })}
              />
              <Switch
                checked={asg.lockNext}
                label="Lock the next chapter until this is approved"
                onChange={(v) => saveAssignment({ lockNext: v })}
              />
            </div>
            <div className="mb-3">
              <span className="form-label">Files that go with the brief</span>
              <div className="tiny muted mb-2">
                Learners on this course can open these. Nobody else can.
              </div>
              {asg.fileIds.length === 0 ? (
                <p className="tiny muted">
                  A rubric or a starter file. Datasets go on the topic instead.
                </p>
              ) : (
                <div className="files mb-2">
                  {asg.fileIds.map((fid) => (
                    <div className="file" key={fid}>
                      <span className="ext">file</span>
                      <span className="mono tiny">{fid.slice(0, 8)}</span>
                      <button
                        className="btn btn-s btn-x"
                        style={{ marginLeft: 'auto' }}
                        onClick={() => run(
                          () => api.del(`/super/catalogue/chapters/${id}/assignment/files/${fid}`),
                          'File removed.')}
                      >
                        Remove
                      </button>
                    </div>
                  ))}
                </div>
              )}
              <FileUpload
                purpose="ASSIGNMENT"
                linkedId={id}
                label="Attach a brief, rubric or starter file"
                onUploaded={(f) => run(
                  () => api.post(`/super/catalogue/chapters/${id}/assignment/files`, { fileId: f.id }),
                  `${f.filename} attached.`)}
              />
            </div>
          </div>
        </div>
      </Card>

      {/*
        * A mentor reaches this page to set and mark the work, and nothing else.
        *
        * A chapter is shared content: its topics and its test belong to every cohort at
        * once, including ones already through it, so rewriting them is the office's call.
        * The server enforces the same split, and this only stops a mentor being shown
        * controls that would be refused.
        */}
      {authoring && (
      <div className="grid g2">
        <div>
          <div id="chapter-test" />
          <Card
            title="Chapter test"
            note="Questions are written, not generated"
            actions={(
              <Switch
                checked={c.test.enabled}
                label={c.test.enabled ? 'On' : 'Off'}
                onChange={(v) => saveTest({ enabled: v })}
              />
            )}
          >
            {questions.length === 0 ? (
              <Empty title="No questions yet" icon="features">
                A test switched on with no questions is what the readiness panel flags.
              </Empty>
            ) : (
              <div style={{ marginBottom: 12 }}>
                {questions.map((q, qi) => (
                  <div className="q" key={q.id}>
                    <span className="drag">{String(qi + 1).padStart(2, '0')}</span>
                    <div className="qb">
                      <b>
                        {q.prompt} {q.draft && <Tag kind="wait">Draft</Tag>}
                        {(q.marks || 1) !== 1 && <Tag kind="batch">{q.marks} marks</Tag>}
                      </b>
                      <div className="qmeta">
                        {q.options.map((o, oi) => (
                          <span key={oi} style={oi === q.correctIndex
                            ? { color: 'var(--teal-600)', fontWeight: 600 } : undefined}>
                            {o}
                          </span>
                        ))}
                      </div>
                    </div>
                    <div className="d-flex gap-2 align-items-start">
                      <button className="btn btn-s" onClick={() => questionForm(q)}>
                        {q.draft ? 'Review' : 'Edit'}
                      </button>
                      <button className="btn btn-s btn-x" onClick={() => removeQuestion(q)}>
                        Delete
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
            <div className="d-flex gap-2 align-items-center flex-wrap">
              <button className="btn btn-s" onClick={addQuestion}>Write a question</button>
              <button className="btn btn-s" onClick={draft}
                title="The model writes five questions from this chapter, as drafts for you to review">
                Draft five with AI
              </button>
              <label className="d-flex align-items-center gap-2 tiny muted">
                Pass
                <input className="form-control form-control-sm" style={{ width: 74 }} type="number"
                  defaultValue={c.test.passMark}
                  onBlur={(e) => saveTest({ passMark: Number(e.target.value) || 60 })} />
              </label>
              <label className="d-flex align-items-center gap-2 tiny muted">
                Attempts
                <input className="form-control form-control-sm" style={{ width: 74 }} type="number"
                  defaultValue={c.test.attempts}
                  onBlur={(e) => saveTest({ attempts: Number(e.target.value) || 2 })} />
              </label>
            </div>
          </Card>
        </div>

        <div>
          <div id="chapter-teachback" />
          <Card
            title="Teach back"
            note="They explain it back, and the model marks the understanding"
            actions={(
              <Switch
                checked={c.teachback.enabled}
                label={c.teachback.enabled ? 'On' : 'Off'}
                onChange={(v) => saveTeachback({ enabled: v })}
              />
            )}
          >
            <label className="mb-3">
              <span className="form-label">Prompt shown to the learner</span>
              <textarea className="form-control" rows={3} defaultValue={c.teachback.prompt || ''}
                onBlur={(e) => e.target.value !== c.teachback.prompt
                  && saveTeachback({ prompt: e.target.value })} />
            </label>
            <div style={{ display: 'grid', gap: 10 }}>
              <Switch
                checked={c.teachback.modelValidates}
                label="The model marks the explanation"
                onChange={(v) => saveTeachback({ modelValidates: v })}
              />
              <Switch
                checked={c.teachback.mentorSees}
                label="The mentor sees the score"
                onChange={(v) => saveTeachback({ mentorSees: v })}
              />
            </div>
            <div className="tiny muted" style={{ marginTop: 10 }}>
              Scored on understanding, not wording. It falls back to a rubric when the model
              is unreachable, and the score appears on the learner record.
            </div>
          </Card>
        </div>
      </div>
      )}

      <Drawer
        open={!!editing}
        title={editing?.id ? 'Edit topic' : 'Add topic'}
        subtitle={c.title}
        onClose={() => setEditing(null)}
      >
        {editing && (
          <TopicForm
            topic={editing}
            onSaved={async () => { setEditing(null); await load() }}
          />
        )}
      </Drawer>
    </Page>
  )
}

/**
 * A topic and its video in one form.
 *
 * They are together because splitting them across two screens is how a course ends up
 * with topics that play nothing, and the id has to be captured somewhere anyway. Putting
 * a new id into a topic that already has one is a rotation, which is the same operation
 * as fixing a leak.
 */
function TopicForm({ topic, onSaved }) {
  const toast = useToast()
  const [form, setForm] = useState({
    title: topic.title || '',
    summary: topic.summary || '',
    codeRunner: topic.codeRunner || 'NONE',
    videoExternalId: '',
    videoProvider: topic.videoProvider || 'YOUTUBE'
  })
  const [busy, setBusy] = useState(false)
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }))
  const parsed = parseVideo(form.videoExternalId, form.videoProvider)

  const save = async () => {
    setBusy(true)
    try {
      await api.post('/super/catalogue/topics', {
        id: topic.id || null,
        chapterId: topic.chapterId,
        ...form,
        durationMin: 0
      })
      toast.push('Topic saved.')
      onSaved()
    } catch (e) { toast.push(e.message, 'bad') } finally { setBusy(false) }
  }

  return (
    <div className="d-grid gap-3">
      <label className="mb-3">
        <span className="form-label">Title</span>
        <input className="form-control" value={form.title} onChange={set('title')} />
      </label>
      <label className="mb-3">
        <span className="form-label">One line</span>
        <input className="form-control" value={form.summary} onChange={set('summary')} />
      </label>
      {/*
        * No minutes field.
        *
        * It was typed in by hand and defaulted to 15, so it was a guess that then got
        * shown to learners as fact in three places. Videos here have no fixed length,
        * and a made up number is worse than no number.
        */}
      <div className="row g-3">
        <label className="mb-3">
        <span className="form-label">Code runner</span>
          <select className="form-control" value={form.codeRunner} onChange={set('codeRunner')}>
            <option value="NONE">None</option>
            <option value="PYTHON">Python</option>
          </select>
        </label>
      </div>

      <VideoField
        value={form.videoExternalId}
        provider={form.videoProvider}
        onChange={(v) => setForm((f) => ({ ...f, videoExternalId: v }))}
        label={topic.hasVideo ? 'Replace the video' : 'Video link'}
        existingMasked={topic.videoMasked}
      />
      {topic.hasVideo && (
        <p className="small text-muted">Leave blank to keep the current video.</p>
      )}

      <div className="d-flex gap-2 align-items-center flex-wrap">
        <button className="btn btn-pib" onClick={save} disabled={busy || !form.title.trim()
          || (!!form.videoExternalId.trim() && !parsed.id)}>
          {busy ? 'Saving' : 'Save topic'}
        </button>
      </div>

      {topic.id && (
        <>
          <hr />
          <h4>Material on this topic</h4>
          <ResourceList topicId={topic.id} onChanged={onSaved} />
        </>
      )}
    </div>
  )
}
