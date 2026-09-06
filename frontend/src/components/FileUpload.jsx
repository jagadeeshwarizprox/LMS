import { useRef, useState } from 'react'
import { api } from '../api/client'
import { useToast } from '../context/ToastContext'
import Icon from './Icon'

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
 */
export default function FileUpload({
  purpose = 'TASK',
  linkedId,
  onUploaded,
  onAllUploaded,
  multiple = true,
  label = 'Attach a file',
  accept
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
      try {
        const fd = new FormData()
        fd.append('file', picked[i])
        const q = new URLSearchParams({ purpose, ...(linkedId ? { linkedId } : {}) })
        const r = await api.upload(`/files?${q}`, fd)
        done.push(r)
        setRows((rs) => rs.map((row, n) => (n === at
          ? { ...row, state: 'done', id: r.id, note: `${Math.round(r.sizeBytes / 1024)} KB` }
          : row)))
        onUploaded?.(r)
      } catch (e) {
        setRows((rs) => rs.map((row, n) => (n === at
          ? { ...row, state: 'bad', note: e.message }
          : row)))
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

  const remove = (at) => setRows((rs) => rs.filter((_, n) => n !== at))
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
            {multiple
              ? 'Drop them here, or click to choose. Notebooks, scripts, documents, sheets, slides, images and archives.'
              : 'Drop it here, or click to choose.'}
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
