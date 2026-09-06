import { useEffect, useRef } from 'react'
import Icon from './Icon'

/**
 * A side panel for looking at one record without leaving the list. The list keeps
 * its scroll position, which is the whole point: an admin working through thirty
 * learners should never lose their place to read one of them.
 */
export default function Drawer({ open, wide, title, subtitle, onClose, children, footer }) {
  const opener = useRef(null)

  useEffect(() => {
    if (!open) return
    opener.current = document.activeElement
    const onKey = (e) => e.key === 'Escape' && onClose()
    document.addEventListener('keydown', onKey)
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = ''
      opener.current?.focus?.()
    }
  }, [open, onClose])

  if (!open) return null

  return (
    <div className="drw-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <aside className={`drw${wide ? ' drw-wide' : ''}`} role="dialog" aria-modal="true" aria-label={title}>
        <header className="drw-head">
          <div>
            {subtitle && <div className="eyebrow mb-1">{subtitle}</div>}
            <h3 className="mb-0">{title}</h3>
          </div>
          <button className="dlg-x" onClick={onClose} aria-label="Close">
            <Icon name="close" size={16} />
          </button>
        </header>
        <div className="drw-body">{children}</div>
        {footer && <footer className="drw-foot">{footer}</footer>}
      </aside>
    </div>
  )
}
