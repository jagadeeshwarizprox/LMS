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

  const [touched, setTouched] = useState(false)
  const missing = fields.filter((f) => f.required && !String(values[f.name] || '').trim())
  const missingNames = new Set(missing.map((f) => f.name))
  /*
   * A field can carry its own rule. Phone and link boxes used to accept anything and the
   * refusal arrived from the server after the dialog had already closed, which loses
   * whatever else had been typed into it.
   */
  const problems = Object.fromEntries(fields
    .map((f) => [f.name, f.validate ? f.validate(values[f.name]) : null])
    .filter(([, msg]) => msg))
  const wordOk = !confirmWord || word.trim().toLowerCase() === confirmWord.toLowerCase()
  const ready = missing.length === 0 && Object.keys(problems).length === 0 && wordOk && !busy

  const submit = (e) => {
    e?.preventDefault?.()
    if (!ready) { setTouched(true); return }
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

        {/*
          * The fields scroll and the buttons do not.
          *
          * The panel had no height limit, so a dialog with seven fields in it grew past
          * the bottom of the window and took the footer with it. The confirm button was
          * still there and simply could not be reached or scrolled to, which is why
          * writing a quiz question could only be finished by pressing return and looked
          * from the outside like a screen with no save button on it.
          */}
        <div className="dlg-scroll">
        {body && <p className="dlg-body">{body}</p>}

        {fields.map((f) => (
          <div className="dlg-field" key={f.name}>
            <label className="form-label" htmlFor={`dlg-${f.name}`}>
              {f.label}{f.required && <span className="dlg-req">required</span>}
            </label>
            {f.options ? (
              <select
                id={`dlg-${f.name}`}
                className={`form-select ${touched && missingNames.has(f.name) ? 'is-invalid' : ''}`}
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
                className={`form-control ${touched && missingNames.has(f.name) ? 'is-invalid' : ''}`}
                rows={3}
                placeholder={f.placeholder}
                value={values[f.name]}
                onChange={(e) => setValues((v) => ({ ...v, [f.name]: e.target.value }))}
              />
            ) : (
              <input
                id={`dlg-${f.name}`}
                className={`form-control ${problems[f.name] || (touched && missingNames.has(f.name)) ? 'is-invalid' : ''}`}
                type={f.type || 'text'}
                min={f.min}
                max={f.max}
                placeholder={f.placeholder}
                value={values[f.name]}
                onChange={(e) => setValues((v) => ({ ...v, [f.name]: e.target.value }))}
              />
            )}
            {problems[f.name] && <div className="dlg-problem">{problems[f.name]}</div>}
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
        </div>

        <div className="dlg-foot">
          {/*
            * A confirm button that is simply dead is the single most reported fault in
            * this product: a question drafted by the model has no explanation, the Why
            * box shows its placeholder, the box looks filled, and Save does nothing with
            * nothing said. The button now stays pressable and the press names what is
            * still missing.
            */}
          {touched && missing.length > 0 && (
            <span className="dlg-blocked">
              Still needed: {missing.map((f) => f.label).join(', ')}
            </span>
          )}
          <button type="button" className="btn btn-quiet" onClick={() => onClose(null)}>
            {cancelLabel}
          </button>
          <button
            type="submit"
            className={intent === 'danger' ? 'btn btn-danger' : 'btn btn-pib'}
            disabled={busy}
          >
            {busy && <Icon name="spinner" size={14} className="spin me-2" />}
            {confirmLabel}
          </button>
        </div>
      </form>
    </div>
  )
}
