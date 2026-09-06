import { useState } from 'react'
import { downloadFile } from '../api/client'
import { useToast } from '../context/ToastContext'
import Icon from './Icon'
import SecurePlayer from './SecurePlayer'
import Drawer from './Drawer'
import DocViewer, { kindOf } from './DocViewer'
import PythonRunner from './PythonRunner'
import { Tag } from './Ui'

const KIND = {
  VIDEO: { label: 'Video', icon: 'recordings' },
  NOTES: { label: 'Notes', icon: 'intake' },
  CODE: { label: 'Code', icon: 'projects' },
  DATASET: { label: 'Dataset', icon: 'batches' },
  SLIDES: { label: 'Slides', icon: 'catalogue' },
  LINK: { label: 'Link', icon: 'resources' },
  TEXT: { label: 'Note', icon: 'faqs' }
}
const size = (b) => (b > 1048576 ? `${(b / 1048576).toFixed(1)} MB` : `${Math.round(b / 1024)} KB`)

/**
 * Everything attached to this topic, in the order the teacher put it.
 *
 * Videos play here rather than opening somewhere else. Files download through the
 * authenticated client, because a plain link carries no credentials and would come
 * back refused.
 */
export default function Materials({ resources = [], codeRunner = 'NONE',
                                    onVideoProgress, onVideoEnded }) {
  const toast = useToast()
  const [playing, setPlaying] = useState(() => resources.find((r) => r.kind === 'VIDEO')?.id)
  const [viewing, setViewing] = useState(null)
  const [python, setPython] = useState(null)

  if (resources.length === 0) return null

  const grab = (r) =>
    downloadFile(r.fileId, r.filename).catch((e) => toast.push(e.message, 'bad'))

  /* every dataset on this topic is written into Python's filesystem, so
     pd.read_csv("retail-sales.csv") works with the name the learner can see */
  const datasets = resources.filter((r) => r.kind === 'DATASET' && r.fileId)

  const openPython = (code) => { setViewing(null); setPython({ code }) }

  return (
    <div className="materials">
      {resources.map((r) => {
        if (r.kind === 'VIDEO') {
          return (
            <div className="material-video" key={r.id}>
              {playing === r.id ? (
                <SecurePlayer
                  videoRef={r.videoRef}
                  poster={r.title}
                  onProgress={onVideoProgress}
                  onEnded={onVideoEnded}
                />
              ) : (
                <button className="material-row as-btn" onClick={() => setPlaying(r.id)}>
                  <span className="material-icon"><Icon name="recordings" size={16} /></span>
                  <span className="material-body">
                    <strong>{r.title}</strong>
                    {r.note && <span className="small muted d-block">{r.note}</span>}
                  </span>
                  <span className="material-action">Play</span>
                </button>
              )}
              {playing === r.id && r.note && <div className="small muted mt-2">{r.note}</div>}
            </div>
          )
        }

        if (r.kind === 'TEXT') {
          return (
            <div className="material-text" key={r.id}>
              <strong>{r.title}</strong>
              <p className="mb-0 mt-1">{r.body}</p>
            </div>
          )
        }

        const isLink = r.kind === 'LINK'
        const readable = !isLink && r.filename && kindOf(r.filename) !== 'none'
        return (
          <a
            className="material-row"
            key={r.id}
            href={isLink ? r.url : undefined}
            target={isLink ? '_blank' : undefined}
            rel={isLink ? 'noreferrer' : undefined}
            onClick={isLink ? undefined : (e) => {
              e.preventDefault()
              /* opening beats downloading: a notebook nobody opens is a notebook
                 nobody reads */
              if (readable) setViewing(r)
              else if (r.downloadable) grab(r)
            }}
          >
            <span className={`material-icon k-${r.kind.toLowerCase()}`}>
              <Icon name={KIND[r.kind]?.icon || 'resources'} size={16} />
            </span>
            <span className="material-body">
              <strong>{r.title}</strong>
              <span className="small muted d-block">
                {r.note || KIND[r.kind]?.label}
                {r.filename && ` · ${r.filename} · ${size(r.sizeBytes)}`}
              </span>
            </span>
            {!r.required && <Tag kind="batch">Optional</Tag>}
            <span className="material-action">
              {isLink ? 'Open' : readable ? 'Open' : r.downloadable ? 'Download' : 'View only'}
            </span>
          </a>
        )
      })}

      {/* only where the topic asked for it: an LMS does not know what it teaches,
          and "try it in Python" on a topic about interview technique is noise */}
      {codeRunner === 'PYTHON' && (
        <button className="material-row as-btn python-cta" onClick={() => setPython({ code: null })}>
          <span className="material-icon k-code"><Icon name="projects" size={16} /></span>
          <span className="material-body">
            <strong>Try it in Python</strong>
            <span className="small muted d-block">
              Runs in your browser. {datasets.length > 0
                ? `${datasets.map((d) => d.filename).join(', ')} loaded and ready.`
                : 'pandas, numpy and matplotlib are ready.'}
            </span>
          </span>
          <span className="material-action">Open</span>
        </button>
      )}

      <Drawer
        open={Boolean(viewing)}
        title={viewing?.title || ''}
        subtitle={viewing?.filename}
        onClose={() => setViewing(null)}
        footer={viewing && viewing.downloadable && (
          <button className="btn btn-quiet" onClick={() => grab(viewing)}>Download</button>
        )}
      >
        {viewing && (
          <DocViewer
            fileId={viewing.fileId}
            filename={viewing.filename}
            onRunPython={openPython}
          />
        )}
      </Drawer>

      <Drawer
        open={Boolean(python)}
        title="Python"
        subtitle="Running in your browser"
        onClose={() => setPython(null)}
      >
        {python && <PythonRunner initialCode={python.code} datasets={datasets} />}
      </Drawer>
    </div>
  )
}
