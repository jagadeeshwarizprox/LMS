import { useEffect, useRef, useState } from 'react'
import { api, downloadFile } from '../api/client'
import { useToast } from '../context/ToastContext'
import { useDialog } from '../components/Dialog'
import Icon from './Icon'
import VideoField, { parseVideo } from './VideoField'
import { Empty, Tag } from './Ui'

const KINDS = [
  ['VIDEO', 'Video', 'recordings', 'Plays inside the LMS, watermarked and tracked.'],
  ['NOTES', 'Notes', 'intake', 'A PDF or document they read alongside the video.'],
  ['CODE', 'Code', 'projects', 'A notebook, a script, or a zip of starter files.'],
  ['DATASET', 'Dataset', 'batches', 'The CSV or workbook the exercise runs on.'],
  ['SLIDES', 'Slides', 'catalogue', 'What you presented in the session.'],
  ['LINK', 'Link', 'resources', 'Documentation or an article, opened in a new tab.'],
  ['TEXT', 'Written note', 'faqs', 'A short instruction, shown inline.']
]
const KIND = Object.fromEntries(KINDS.map(([k, label, icon]) => [k, { label, icon }]))
const FILE_KINDS = ['NOTES', 'CODE', 'DATASET', 'SLIDES']

const size = (b) => (b > 1048576 ? `${(b / 1048576).toFixed(1)} MB` : `${Math.round(b / 1024)} KB`)

/**
 * Everything attached to a topic.
 *
 * A topic used to hold one video and nothing else, so the notebook, the dataset and
 * the slides that a session actually produces had nowhere to go. Order matters here:
 * this is the sequence a learner works through.
 */
export default function ResourceList({ topicId, onChanged }) {
  const toast = useToast()
  const { ask } = useDialog()
  const [rows, setRows] = useState(null)
  const [busy, setBusy] = useState(false)
  const drop = useRef(null)
  const picker = useRef(null)

  const load = () =>
    api.get(`/super/catalogue/topics/${topicId}/resources`).then(setRows).catch(() => setRows([]))
  useEffect(() => { load() }, [topicId])
  if (!rows) return null

  const after = async (msg) => { toast.push(msg); await load(); await onChanged?.() }

  const addVideo = async () => {
    const r = await ask({
      title: 'Add a video',
      body: 'Upload to YouTube as unlisted and paste the link. Only the id is stored, and it is '
          + 'handed to the browser at the moment someone presses play.',
      fields: [
        { name: 'title', label: 'Title', required: true, placeholder: 'What this video covers' },
        { name: 'link', label: 'Video link', required: true, placeholder: 'https://youtu.be/...' },
        { name: 'note', label: 'One line', placeholder: 'Optional' }
      ],
      confirmLabel: 'Add video'
    })
    if (!r) return
    if (!parseVideo(r.link, 'YOUTUBE').id) {
      toast.push('That video link is not one we can play.', 'bad')
      return
    }
    try {
      await api.post('/super/catalogue/resources', {
        topicId, kind: 'VIDEO', title: r.title, note: r.note, videoLink: r.link
      })
      after('Video added.')
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const addLink = async () => {
    const r = await ask({
      title: 'Add a link',
      fields: [
        { name: 'title', label: 'Title', required: true, placeholder: 'What this link is' },
        { name: 'url', label: 'Link', required: true, placeholder: 'https://' },
        { name: 'note', label: 'One line', placeholder: 'Optional' }
      ],
      confirmLabel: 'Add link'
    })
    if (!r) return
    try {
      await api.post('/super/catalogue/resources',
        { topicId, kind: 'LINK', title: r.title, url: r.url, note: r.note })
      after('Link added.')
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const addText = async () => {
    const r = await ask({
      title: 'Add a written note',
      body: 'Shown inline, above the materials. Good for an instruction the video does not cover.',
      fields: [
        { name: 'title', label: 'Title', required: true },
        { name: 'body', label: 'What it says', required: true, multiline: true }
      ],
      confirmLabel: 'Add note'
    })
    if (!r) return
    try {
      await api.post('/super/catalogue/resources',
        { topicId, kind: 'TEXT', title: r.title, body: r.body })
      after('Note added.')
    } catch (e) { toast.push(e.message, 'bad') }
  }

  /** Files can be dropped in a handful at a time; each becomes its own resource. */
  const takeFiles = async (fileList, kind = 'NOTES') => {
    const files = [...fileList]
    if (files.length === 0) return
    setBusy(true)
    try {
      const uploaded = []
      for (const f of files) {
        const fd = new FormData()
        fd.append('file', f)
        const r = await api.upload(`/files?purpose=RESOURCE&linkedId=${topicId}`, fd)
        uploaded.push({ id: r.id, filename: r.filename })
      }
      await api.post(`/super/catalogue/topics/${topicId}/resources/bulk`, { files: uploaded, kind })
      after(`${uploaded.length} file${uploaded.length > 1 ? 's' : ''} attached.`)
    } catch (e) {
      toast.push(e.message, 'bad')
    } finally { setBusy(false) }
  }

  const edit = async (r) => {
    const res = await ask({
      title: `Edit ${r.title}`,
      fields: [
        { name: 'title', label: 'Title', required: true, value: r.title },
        { name: 'note', label: 'One line', value: r.note || '' },
        { name: 'required', label: 'Counts towards finishing', required: true,
          value: r.required ? 'yes' : 'no',
          options: [{ value: 'yes', label: 'Required' }, { value: 'no', label: 'Optional' }] },
        { name: 'beforeSession', label: 'Available', required: true,
          value: r.beforeSession ? 'yes' : 'no',
          options: [{ value: 'yes', label: 'Straight away' },
                    { value: 'no', label: 'After the session' }] },
        { name: 'trackScope', label: 'Shown to', required: true, value: r.trackScope,
          options: [{ value: 'BOTH', label: 'Everyone' }, { value: 'PREMIUM', label: 'Premium' },
                    { value: 'BATCH', label: 'Batch' }] }
      ],
      confirmLabel: 'Save'
    })
    if (!res) return
    try {
      await api.post('/super/catalogue/resources', {
        id: r.id, topicId, kind: r.kind, title: res.title, note: res.note,
        required: res.required === 'yes', beforeSession: res.beforeSession === 'yes',
        trackScope: res.trackScope,
        url: r.url, body: r.body, fileId: r.fileId
      })
      after('Saved.')
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const remove = async (r) => {
    try {
      await api.del(`/super/catalogue/resources/${r.id}`)
      after('Removed.')
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const move = async (id, dir) => {
    const ids = rows.map((x) => x.id)
    const i = ids.indexOf(id)
    const j = i + dir
    if (j < 0 || j >= ids.length) return
    ;[ids[i], ids[j]] = [ids[j], ids[i]]
    await api.post(`/super/catalogue/topics/${topicId}/resources/reorder`, { ids })
    after('Order saved.')
  }

  return (
    <>
      <div className="res-add">
        {KINDS.map(([k, label, icon, note]) => (
          <button
            key={k}
            className="res-add-btn"
            title={note}
            onClick={() => {
              if (k === 'VIDEO') addVideo()
              else if (k === 'LINK') addLink()
              else if (k === 'TEXT') addText()
              else { picker.current.dataset.kind = k; picker.current.click() }
            }}
          >
            <Icon name={icon} size={16} />
            {label}
          </button>
        ))}
        <input
          ref={picker}
          type="file"
          multiple
          hidden
          onChange={(e) => { takeFiles(e.target.files, picker.current.dataset.kind || 'NOTES'); e.target.value = '' }}
        />
      </div>

      <div
        ref={drop}
        className="res-drop"
        onDragOver={(e) => { e.preventDefault(); drop.current.classList.add('over') }}
        onDragLeave={() => drop.current.classList.remove('over')}
        onDrop={(e) => {
          e.preventDefault()
          drop.current.classList.remove('over')
          takeFiles(e.dataTransfer.files)
        }}
      >
        <Icon name="import" size={16} />
        {busy ? 'Uploading' : 'Drop files here to attach several at once'}
      </div>

      {rows.length === 0 ? (
        <Empty title="Nothing attached yet" icon="resources">
          A learner reaching this topic would see an empty page.
        </Empty>
      ) : (
        <table className="table table-pib mb-0 res-table">
          <tbody>
            {rows.map((r) => (
              <tr key={r.id}>
                <td style={{ width: 40 }}>
                  <span className={`res-kind k-${r.kind.toLowerCase()}`} title={KIND[r.kind]?.label}>
                    <Icon name={KIND[r.kind]?.icon || 'resources'} size={15} />
                  </span>
                </td>
                <td>
                  <strong>{r.title}</strong>
                  {r.note && <div className="small muted">{r.note}</div>}
                  <div className="res-meta">
                    {r.kind === 'VIDEO' && r.videoMasked && (
                      <span className="mono">{r.videoMasked}</span>
                    )}
                    {r.filename && <span className="mono">{r.filename} · {size(r.sizeBytes)}</span>}
                    {r.url && <span className="mono">{r.url}</span>}
                    {!r.required && <Tag kind="batch">Optional</Tag>}
                    {!r.beforeSession && <Tag kind="wait">After the session</Tag>}
                    {r.trackScope !== 'BOTH' && <Tag kind="batch">{r.trackScope}</Tag>}
                  </div>
                </td>
                <td className="text-end res-actions">
                  {r.fileId && (
                    <button className="icon-btn" title="Download" aria-label="Download this file"
                      onClick={() => downloadFile(r.fileId, r.filename)}>
                      <Icon name="import" size={14} />
                    </button>
                  )}
                  <button className="icon-btn" title="Move up" aria-label="Move this up" onClick={() => move(r.id, -1)}>
                    <Icon name="chevron" size={14} className="flip" />
                  </button>
                  <button className="icon-btn" title="Move down" aria-label="Move this down" onClick={() => move(r.id, 1)}>
                    <Icon name="chevron" size={14} />
                  </button>
                  <button className="btn btn-quiet ms-1" onClick={() => edit(r)}>Edit</button>
                  <button className="btn btn-quiet" onClick={() => remove(r)}>Remove</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}
