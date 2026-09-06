import { useState } from 'react'
import { api } from '../api/client'

/**
 * Per chapter, and only this chapter. It is a study aid between sessions, not a
 * replacement for the mentor, and it says so when a question is outside its scope.
 */
export default function DoubtBlock({ chapterId }) {
  const [q, setQ] = useState('')
  const [thread, setThread] = useState([])
  const [busy, setBusy] = useState(false)

  const ask = async () => {
    const question = q.trim()
    if (!question) return
    setBusy(true)
    setThread((t) => [...t, { role: 'you', text: question }])
    setQ('')
    try {
      const r = await api.post(`/learner/chapters/${chapterId}/doubt`, { question })
      setThread((t) => [...t, { role: 'ai', text: r.answer, available: r.available }])
    } catch (e) {
      setThread((t) => [...t, { role: 'ai', text: e.message, available: false }])
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="doubt">
      <div className="eyebrow mb-2">Stuck on this chapter?</div>
      {thread.map((m, i) => (
        <div key={i} className={`doubt-msg ${m.role}`}>
          {m.text}
          {m.role === 'ai' && m.available === false && (
            <div className="small muted mt-1">Bring this to your doubt clearing session.</div>
          )}
        </div>
      ))}
      <div className="d-flex gap-2 mt-2">
        <input
          className="form-control"
          placeholder="Ask about this chapter"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && ask()}
        />
        <button className="btn btn-pib" onClick={ask} disabled={busy || !q.trim()}>
          {busy ? 'Thinking' : 'Ask'}
        </button>
      </div>
      <div className="small muted mt-2">
        Answers cover this chapter only. Anything wider belongs in a session with your mentor.
      </div>
    </div>
  )
}
