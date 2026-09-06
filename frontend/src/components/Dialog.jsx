import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react'
import Icon from './Icon'

/**
 * Real dialogs, replacing the browser prompt boxes this product was using for
 * rotating a video, moving a mentor and scheduling a mock. A prompt cannot validate,
 * cannot explain what an action does, and cannot be styled. These can do all three.
 *
 * Focus is trapped while open and returned to whatever opened it, escape closes,
 * and a destructive action has to be understood before it can be confirmed.
 */

const DialogContext = createContext(null)

export function DialogProvider({ children }) {
  const [dialog, setDialog] = useState(null)

  /** ask({...}) resolves with the values, or null if the person backed out. */
  const ask = useCallback((config) => new Promise((resolve) => {
    setDialog({ ...config, resolve })
  }), [])

  const close = (value) => {
    dialog?.resolve?.(value)
    setDialog(null)
  }

  return (
    <DialogContext.Provider value={{ ask }}>
      {children}
      {dialog && <DialogShell config={dialog} onClose={close} />}
    </DialogContext.Provider>
  )
}

export const useDialog = () => useContext(DialogContext)

function DialogShell({ config, onClose }) {
  const {
    title, intent = 'default', body, fields = [], confirmLabel = 'Confirm',
    cancelLabel = 'Cancel', confirmWord
  } = config

  const [values, setValues] = useState(() =>
    Object.fromEntries(fields.map((f) => [f.name, f.value ?? (f.options ? '' : '')])))
  const [word, setWord] = useState('')
  const [busy, setBusy] = useState(false)
  const panel = useRef(null)
  const opener = useRef(typeof document !== 'undefined' ? document.activeElement : null)

  useEffect(() => {
    const first = panel.current?.querySelector('input, select, textarea, button')
    first?.focus()
    const onKey = (e) => {
      if (e.key === 'Escape') onClose(null)
      if (e.key !== 'Tab') return
      const focusable = panel.current?.querySelectorAll(
        'button, input, select, textarea, [href]')
      if (!focusable?.length) return
      const list = [...focusable].filter((el) => !el.disabled)
      const first_ = list[0], last = list[list.length - 1]
      if (e.shiftKey && document.activeElement === first_) { e.preventDefault(); last.focus() }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first_.focus() }
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      opener.current?.focus?.()
    }
  }, [onClose])

  const missing = fields.filter((f) => f.required && !String(values[f.name] || '').trim())
  const wordOk = !confirmWord || word.trim().toLowerCase() === confirmWord.toLowerCase()
  const ready = missing.length === 0 && wordOk && !busy

  const submit = (e) => {
    e?.preventDefault?.()
    if (!ready) return
    setBusy(true)
    onClose(values)
  }

  return (
    <div className="dlg-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose(null)}>
      <form
        className={`dlg dlg-${intent}`}
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onSubmit={submit}
      >
        <div className="dlg-head">
          <h3>{title}</h3>
          <button type="button" className="dlg-x" onClick={() => onClose(null)} aria-label="Close">
            <Icon name="close" size={16} />
          </button>
        </div>

        {body && <p className="dlg-body">{body}</p>}

        {fields.map((f) => (
          <div className="dlg-field" key={f.name}>
            <label className="form-label" htmlFor={`dlg-${f.name}`}>
              {f.label}{f.required && <span className="dlg-req">required</span>}
            </label>
            {f.options ? (
              <select
                id={`dlg-${f.name}`}
                className="form-select"
                value={values[f.name]}
                onChange={(e) => setValues((v) => ({ ...v, [f.name]: e.target.value }))}
              >
                <option value="">{f.placeholder || 'Choose one'}</option>
                {f.options.map((o) => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            ) : f.multiline ? (
              <textarea
                id={`dlg-${f.name}`}
                className="form-control"
                rows={3}
                placeholder={f.placeholder}
                value={values[f.name]}
                onChange={(e) => setValues((v) => ({ ...v, [f.name]: e.target.value }))}
              />
            ) : (
              <input
                id={`dlg-${f.name}`}
                className="form-control"
                type={f.type || 'text'}
                placeholder={f.placeholder}
                value={values[f.name]}
                onChange={(e) => setValues((v) => ({ ...v, [f.name]: e.target.value }))}
              />
            )}
            {f.hint && <div className="dlg-hint">{f.hint}</div>}
          </div>
        ))}

        {confirmWord && (
          <div className="dlg-field">
            <label className="form-label" htmlFor="dlg-word">
              Type <strong>{confirmWord}</strong> to confirm
            </label>
            <input
              id="dlg-word"
              className="form-control"
              value={word}
              onChange={(e) => setWord(e.target.value)}
              autoComplete="off"
            />
          </div>
        )}

        <div className="dlg-foot">
          <button type="button" className="btn btn-quiet" onClick={() => onClose(null)}>
            {cancelLabel}
          </button>
          <button
            type="submit"
            className={intent === 'danger' ? 'btn btn-danger' : 'btn btn-pib'}
            disabled={!ready}
          >
            {busy && <Icon name="spinner" size={14} className="spin me-2" />}
            {confirmLabel}
          </button>
        </div>
      </form>
    </div>
  )
}
