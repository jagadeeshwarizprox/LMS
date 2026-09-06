import { useEffect, useState } from 'react'
import { api } from '../api/client'

/**
 * Answers rendered where the question comes up. Collapsed by default so it never
 * competes with the work, but present, which is the whole point.
 */
export default function FaqBlock({ placement, track = 'BOTH', title = 'Common questions', publicMode }) {
  const [items, setItems] = useState([])
  const [open, setOpen] = useState(null)

  useEffect(() => {
    const path = publicMode ? '/public/faqs' : `/faqs?placement=${placement}&track=${track}`
    api.get(path).then(setItems).catch(() => setItems([]))
  }, [placement, track, publicMode])

  if (items.length === 0) return null

  return (
    <div className="faq-block">
      <div className="eyebrow mb-2">{title}</div>
      {items.map((f) => (
        <div key={f.id} className={`faq-item ${open === f.id ? 'open' : ''}`}>
          <button className="faq-q" onClick={() => setOpen(open === f.id ? null : f.id)}>
            <span>{f.question}</span>
            <span className="faq-chev" aria-hidden="true">{open === f.id ? '\u2212' : '+'}</span>
          </button>
          {open === f.id && <div className="faq-a">{f.answer}</div>}
        </div>
      ))}
    </div>
  )
}
