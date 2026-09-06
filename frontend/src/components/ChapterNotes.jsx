import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client'

/** Saves itself a moment after you stop typing, because nobody presses save on notes. */
export default function ChapterNotes({ chapterId }) {
  const [body, setBody] = useState('')
  const [status, setStatus] = useState('')
  const first = useRef(true)

  useEffect(() => {
    api.get(`/learner/chapters/${chapterId}/note`)
      .then((n) => { setBody(n.body || ''); first.current = true })
      .catch(() => {})
  }, [chapterId])

  useEffect(() => {
    if (first.current) { first.current = false; return }
    setStatus('Saving')
    const t = setTimeout(async () => {
      try {
        await api.put(`/learner/chapters/${chapterId}/note`, { body })
        setStatus('Saved')
        setTimeout(() => setStatus(''), 1500)
      } catch { setStatus('Not saved') }
    }, 900)
    return () => clearTimeout(t)
  }, [body, chapterId])

  return (
    <>
      <textarea
        className="form-control"
        rows={5}
        placeholder="Anything you want back before an interview."
        value={body}
        onChange={(e) => setBody(e.target.value)}
      />
      <div className="small muted mt-2" style={{ minHeight: 18 }}>{status}</div>
    </>
  )
}
