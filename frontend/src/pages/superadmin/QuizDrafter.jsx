import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { Card, Empty, Tag } from '../../components/Ui'

/**
 * The model writes candidates, a person publishes them. Drafts are marked and are
 * never served to a learner, so a bad question cannot reach a chapter test by
 * accident. Editing before publishing is the normal path, not the exception.
 */
export default function QuizDrafter({ modules }) {
  const toast = useToast()
  const [moduleId, setModuleId] = useState('')
  const [chapters, setChapters] = useState([])
  const [chapterId, setChapterId] = useState('')
  const [questions, setQuestions] = useState([])
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!moduleId) { setChapters([]); setChapterId(''); return }
    api.get(`/super/modules/${moduleId}/chapters`).then((c) => {
      setChapters(c)
      setChapterId(c[0]?.id || '')
    })
  }, [moduleId])

  const load = (id) => {
    if (!id) { setQuestions([]); return }
    api.get(`/super/chapters/${id}/questions`).then(setQuestions).catch(() => setQuestions([]))
  }
  useEffect(() => { load(chapterId) }, [chapterId])

  const draft = async () => {
    setBusy(true)
    try {
      await api.post(`/super/chapters/${chapterId}/draft-quiz`, { count: 3 })
      toast.push('Drafted. Read each one before publishing it.')
      load(chapterId)
    } catch (e) {
      toast.push(e.message, 'bad')
    } finally {
      setBusy(false)
    }
  }

  const publish = async (q) => {
    try {
      await api.post(`/super/questions/${q.id}/publish`, q)
      toast.push('Published. Learners will see it on the next attempt.')
      load(chapterId)
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const discard = async (q) => {
    try { await api.del(`/super/questions/${q.id}`); load(chapterId) }
    catch (e) { toast.push(e.message, 'bad') }
  }

  const edit = (id, patch) =>
    setQuestions((qs) => qs.map((q) => (q.id === id ? { ...q, ...patch } : q)))

  const drafts = questions.filter((q) => q.draft)
  const live = questions.filter((q) => !q.draft)

  return (
    <Card
      title="Chapter tests"
      note="Draft with the model, then read and publish. Nothing a learner sees was written without a person approving it."
    >
      <div className="row g-2 align-items-end mb-3">
        <div className="col-md-4">
          <label className="form-label">Module</label>
          <select className="form-select" value={moduleId} onChange={(e) => setModuleId(e.target.value)}>
            <option value="">Choose one</option>
            {modules.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
          </select>
        </div>
        <div className="col-md-5">
          <label className="form-label">Chapter</label>
          <select className="form-select" value={chapterId} onChange={(e) => setChapterId(e.target.value)}>
            {chapters.map((c) => <option key={c.id} value={c.id}>{c.title}</option>)}
          </select>
        </div>
        <div className="col-md-3">
          <button className="btn btn-pib w-100" onClick={draft} disabled={!chapterId || busy}>
            {busy ? 'Drafting' : 'Draft 3 questions'}
          </button>
        </div>
      </div>

      {!chapterId ? (
        <Empty title="Pick a chapter" />
      ) : (
        <>
          {drafts.length > 0 && (
            <div className="mb-3">
              <div className="eyebrow mb-2">Waiting for review</div>
              {drafts.map((q) => (
                <div key={q.id} className="draft-q">
                  <input
                    className="form-control mb-2"
                    value={q.prompt}
                    onChange={(e) => edit(q.id, { prompt: e.target.value })}
                  />
                  {q.options.map((o, i) => (
                    <label key={i} className="d-flex gap-2 align-items-center mb-1">
                      <input
                        type="radio"
                        name={q.id}
                        checked={q.correctIndex === i}
                        onChange={() => edit(q.id, { correctIndex: i })}
                      />
                      <input
                        className="form-control"
                        value={o}
                        onChange={(e) => edit(q.id, {
                          options: q.options.map((x, xi) => (xi === i ? e.target.value : x))
                        })}
                      />
                    </label>
                  ))}
                  <input
                    className="form-control mt-2"
                    placeholder="Why that answer is right"
                    value={q.explanation || ''}
                    onChange={(e) => edit(q.id, { explanation: e.target.value })}
                  />
                  <div className="d-flex gap-2 mt-2">
                    <button className="btn btn-pib" onClick={() => publish(q)}>Publish</button>
                    <button className="btn btn-quiet" onClick={() => discard(q)}>Discard</button>
                    <span className="ms-auto"><Tag kind="wait">Draft</Tag></span>
                  </div>
                </div>
              ))}
            </div>
          )}

          <div className="eyebrow mb-2">Live on this chapter</div>
          {live.length === 0 ? (
            <Empty title="No published questions yet">
              This chapter's test is empty until something is published.
            </Empty>
          ) : (
            <table className="table table-pib mb-0">
              <tbody>
                {live.map((q) => (
                  <tr key={q.id}>
                    <td>{q.prompt}</td>
                    <td className="small muted">{q.options[q.correctIndex]}</td>
                    <td className="text-end" style={{ width: 100 }}>
                      <button className="btn btn-quiet" onClick={() => discard(q)}>Remove</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </Card>
  )
}
