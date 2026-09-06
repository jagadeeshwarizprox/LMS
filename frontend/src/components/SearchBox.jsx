import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api/client'

/** One box over the questions and the syllabus, since learners search for both. */
export default function SearchBox({ track = 'BOTH' }) {
  const [q, setQ] = useState('')
  const [res, setRes] = useState(null)
  const [open, setOpen] = useState(false)
  const box = useRef(null)
  const navigate = useNavigate()

  useEffect(() => {
    if (q.trim().length < 2) { setRes(null); return }
    const t = setTimeout(() => {
      api.get(`/faqs/search?q=${encodeURIComponent(q)}&track=${track}`)
        .then((r) => { setRes(r); setOpen(true) })
        .catch(() => setRes(null))
    }, 250)
    return () => clearTimeout(t)
  }, [q, track])

  useEffect(() => {
    const away = (e) => { if (!box.current?.contains(e.target)) setOpen(false) }
    document.addEventListener('click', away)
    return () => document.removeEventListener('click', away)
  }, [])

  return (
    <div className="search-box" ref={box}>
      <input
        className="form-control"
        placeholder="Search help and chapters"
        value={q}
        onChange={(e) => setQ(e.target.value)}
        onFocus={() => res && setOpen(true)}
      />
      {open && res && (
        <div className="search-pop">
          {res.faqs?.map((f) => (
            <div key={f.id} className="search-row">
              <div style={{ fontWeight: 600, fontSize: '.87rem' }}>{f.question}</div>
              <div className="small muted">{f.answer}</div>
            </div>
          ))}
          {res.chapters?.map((c) => (
            <button key={c.id} className="search-row as-btn"
              onClick={() => { setOpen(false); navigate(`/learn/chapter/${c.id}`) }}>
              <span className="eyebrow">{c.module}</span>
              <div style={{ fontWeight: 600, fontSize: '.87rem' }}>{c.title}</div>
            </button>
          ))}
          {res.aiAnswer && (
            <div className="search-row">
              <span className="tag tag-batch">Assistant</span>
              <div className="small mt-1">{res.aiAnswer}</div>
            </div>
          )}
          {!res.faqs?.length && !res.chapters?.length && !res.aiAnswer && (
            <div className="search-row small muted">Nothing found. Ask in your group.</div>
          )}
        </div>
      )}
    </div>
  )
}
