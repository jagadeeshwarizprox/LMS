import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client'

/**
 * Saves itself a moment after you stop typing, because nobody presses save on notes.
 *
 * Keyed on the chapter, so moving from one chapter to the next mounts a fresh editor.
 * One editor used to serve every chapter: text typed into chapter A could still be in
 * the box, or on its save timer, when the id changed to B, and was then written onto B.
 */
export default function ChapterNotes({ chapterId }) {
  return <NotesFor key={chapterId} chapterId={chapterId} />
}

function NotesFor({ chapterId }) {
  const [body, setBody] = useState('')
  const [status, setStatus] = useState('')
  /* loading | ready | error. Nothing is editable, and nothing saves, until the stored
     note is in: saving before then would overwrite it with an empty box */
  const [phase, setPhase] = useState('loading')
  /* only a keystroke makes the note dirty; loading it is not an edit */
  const dirty = useRef(false)

  const fetchNote = () => {
    setPhase('loading')
    api.get(`/learner/chapters/${chapterId}/note`)
      .then((n) => { setBody(n?.body || ''); dirty.current = false; setPhase('ready') })
      .catch(() => setPhase('error'))
  }

  useEffect(() => { fetchNote() }, [chapterId])

  useEffect(() => {
    if (phase !== 'ready' || !dirty.current) return
    setStatus('Saving')
    const t = setTimeout(async () => {
      try {
        await api.put(`/learner/chapters/${chapterId}/note`, { body })
        dirty.current = false
        setStatus('Saved')
        setTimeout(() => setStatus(''), 1500)
      } catch { setStatus('Not saved') }
    }, 900)
    /* a pending save is dropped on the next keystroke and on unmount, which is also
       what a chapter change is now */
    return () => clearTimeout(t)
  }, [body, chapterId, phase])

  if (phase === 'error') {
    return (
      <div className="locked-note">
        Your notes for this chapter could not be loaded, so editing is off to keep them safe.{' '}
        <button className="btn btn-quiet btn-sm" onClick={fetchNote}>Try again</button>
      </div>
    )
  }

  return (
    <>
      <textarea
        className="form-control"
        rows={5}
        placeholder={phase === 'loading' ? 'Loading your notes' : 'Anything you want back before an interview.'}
        value={body}
        disabled={phase !== 'ready'}
        onChange={(e) => { dirty.current = true; setBody(e.target.value) }}
      />
      <div className="small muted mt-2" style={{ minHeight: 18 }}>{status}</div>
    </>
  )
}
