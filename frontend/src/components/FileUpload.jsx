import { useRef, useState } from 'react'
import { uploadWithProgress } from '../api/client'
import { useToast } from '../context/ToastContext'
import Icon from './Icon'

const mb = (bytes) => `${Math.round(bytes / (1024 * 1024))} MB`

/**
 * A real upload. Links to somebody's Drive break within a month, and a learner
 * should not have to think about sharing permissions to hand in a notebook.
 *
 * Several at once, because almost nothing is one file. A notebook comes with the
 * dataset it reads and the chart it produced, and handing those in one at a time meant
 * three round trips through a control that forgot the previous one each time. Each file
 * is its own request, so one rejection does not lose the rest, and a rejected file says
 * which one and why rather than the whole drop failing silently.
 *
 * `onUploaded` still fires per file, so every existing single-file caller keeps working
 * unchanged. `onAllUploaded` gets the whole batch when it settles.
 *
 * `onRemoved` fires with the uploaded file when a finished row is taken off the list.
 * The cross only ever tidied this list, so a file a person removed here was still in
 * whatever the parent had collected and went out with the submission anyway.
 *
 * `maxBytes`, when given, is checked before anything is sent: a recording over the
 * limit used to go all the way up the wire and come back as "Maximum upload size
 * exceeded". The row shows progress while it goes, because a 400 MB file on a home
 * connection takes minutes and "Uploading" on its own reads as stuck.
 */
export default function FileUpload({
  purpose = 'TASK',
  linkedId,
  onUploaded,
  onAllUploaded,
  onRemoved,
  multiple = true,
  label = 'Attach a file',
  accept,
  maxBytes,
  hint
}) {
  const toast = useToast()
  const input = useRef(null)
  const [rows, setRows] = useState([])
  const [busy, setBusy] = useState(false)
  const [drag, setDrag] = useState(false)

  const take = async (list) => {
    const chosen = [...(list || [])]
    if (chosen.length === 0) return
    const picked = multiple ? chosen : chosen.slice(0, 1)

    setBusy(true)
    const started = picked.map((f) => ({ name: f.name, state: 'going', id: null, note: null }))
    const base = multiple ? rows.length : 0
    setRows((r) => (multiple ? [...r, ...started] : started))

    const done = []
    for (let i = 0; i < picked.length; i++) {
      const at = base + i
      const patch = (p) => setRows((rs) => rs.map((row, n) => (n === at ? { ...row, ...p } : row)))
      /* refused here, before a byte goes, with the number the server would have said */
      if (maxBytes && picked[i].size > maxBytes) {
        patch({ state: 'bad', note: `${mb(picked[i].size)} is over the ${mb(maxBytes)} limit.` })
        continue
      }
      try {
        const fd = new FormData()
        fd.append('file', picked[i])
        const q = new URLSearchParams({ purpose, ...(linkedId ? { linkedId } : {}) })
        const r = await uploadWithProgress(`/files?${q}`, fd, (pct, loaded, total) => {
          patch({ pct, note: pct < 100 ? `${pct}% · ${mb(loaded)} of ${mb(total)}` : 'Saving…' })
        })
        done.push(r)
        patch({ state: 'done', id: r.id, file: r, pct: 100,
          note: r.sizeBytes >= 1024 * 1024 ? mb(r.sizeBytes) : `${Math.round(r.sizeBytes / 1024)} KB` })
        onUploaded?.(r)
      } catch (e) {
        patch({ state: 'bad', note: e.message, pct: 0 })
      }
    }

    setBusy(false)
    if (done.length > 0) {
      onAllUploaded?.(done)
      toast.push(done.length === 1
        ? `${done[0].filename} attached.`
        : `${done.length} files attached.`)
    }
  }

  const drop = (e) => {
    e.preventDefault()
    setDrag(false)
    take(e.dataTransfer.files)
  }

  const remove = (at) => {
    const row = rows[at]
    setRows((rs) => rs.filter((_, n) => n !== at))
    if (row?.state === 'done' && row.id) onRemoved?.(row.file || { id: row.id })
  }
  const anyDone = rows.some((r) => r.state === 'done')

  return (
    <div className="upload-wrap">
      <div
        className={`upload ${drag ? 'over' : ''} ${anyDone ? 'done' : ''}`}
        onDragOver={(e) => { e.preventDefault(); setDrag(true) }}
        onDragLeave={() => setDrag(false)}
        onDrop={drop}
        onClick={() => !busy && input.current?.click()}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && !busy && input.current?.click()}
      >
        <input
          ref={input}
          type="file"
          hidden
          multiple={multiple}
          accept={accept}
          onChange={(e) => { take(e.target.files); e.target.value = '' }}
        />
        <Icon name={anyDone ? 'check' : 'import'} size={20} />
        <div>
          <strong>{busy ? 'Uploading' : label}</strong>
          <div className="small muted">
            {hint || (multiple
              ? 'Drop them here, or click to choose. Notebooks, scripts, documents, sheets, slides, images and archives.'
              : 'Drop it here, or click to choose.')}
            {maxBytes ? ` Up to ${mb(maxBytes)}.` : ''}
          </div>
        </div>
      </div>

      {rows.length > 0 && (
        <ul className="upload-list">
          {rows.map((r, at) => (
            <li key={`${r.name}-${at}`} className={`upload-row is-${r.state}`}>
              <Icon
                name={r.state === 'done' ? 'check' : r.state === 'bad' ? 'close' : 'import'}
                size={14}
              />
              <span className="upload-name">{r.name}</span>
              {r.state === 'going' && (
                <span className="upload-bar" role="progressbar" aria-valuemin={0} aria-valuemax={100}
                  aria-valuenow={r.pct || 0}>
                  <span className="upload-bar-fill" style={{ width: `${r.pct || 0}%` }} />
                </span>
              )}
              {r.note && <span className="small muted upload-note">{r.note}</span>}
              {r.state !== 'going' && (
                <button
                  className="upload-x"
                  aria-label={`Remove ${r.name}`}
                  onClick={(e) => { e.stopPropagation(); remove(at) }}
                >
                  <Icon name="close" size={12} />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
