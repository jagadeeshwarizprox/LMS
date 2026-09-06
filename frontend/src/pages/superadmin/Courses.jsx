import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { useDialog } from '../../components/Dialog'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, LoadError, Page, Tag } from '../../components/Ui'

const COLORS = ['#01153A', '#00B0F0', '#008A9D', '#0b6fa8', '#334155', '#7c3aed']

const shade = (hex) => {
  const n = parseInt(hex.slice(1), 16)
  const r = Math.max(0, (n >> 16) - 28), g = Math.max(0, ((n >> 8) & 255) - 18), b = Math.max(0, (n & 255) - 8)
  return '#' + ((r << 16) | (g << 8) | b).toString(16).padStart(6, '0')
}

/**
 * The catalogue.
 *
 * A course is a bundle of modules, and the same module sits in more than one course.
 * That is the whole reason for bundling rather than copying: Python is one module, and
 * editing it once fixes it in Data Analyst, Data Scientist and everywhere else.
 */
export default function Courses() {
  const toast = useToast()
  const { ask } = useDialog()
  const nav = useNavigate()
  const [tree, setTree] = useState(null)
  const [picked, setPicked] = useState([])
  const [naming, setNaming] = useState(null)

  const [loadError, setLoadError] = useState(null)

  const load = () => api.get('/super/catalogue')
    .then((r) => { setTree(r); setLoadError(null) })
    .catch((e) => setLoadError(e.message))
  useEffect(() => { load() }, [])
  if (loadError) {
    return <Page title="Courses and pricing"><LoadError error={loadError} onRetry={load} /></Page>
  }
  if (!tree) return <TableSkeleton />

  /* the modules are chosen here rather than on the next screen, because a course with
     none is the state everything downstream has to guard against */
  const add = async () => {
    if (tree.modules.length === 0) {
      toast.push('Build a module first. A course is a bundle of them.', 'bad')
      return
    }
    setPicked([])
    setNaming(true)
  }

  const create = async () => {
    const r = await ask({
      title: `New course, ${picked.length} module${picked.length === 1 ? '' : 's'}`,
      fields: [
        { name: 'name', label: 'Name', required: true, placeholder: 'Data Analyst' },
        { name: 'tag', label: 'Label on the card', placeholder: 'Career track' },
        { name: 'description', label: 'One line' },
        {
          name: 'color', label: 'Cover colour', value: COLORS[0],
          options: COLORS.map((c) => ({ value: c, label: c }))
        }
      ],
      confirmLabel: 'Create course'
    })
    if (!r) return
    try {
      const saved = await api.post('/super/catalogue/bundles', {
        ...r, moduleIds: picked
      })
      toast.push('Course created.')
      nav(`/super/courses/${saved.id}`)
    } catch (e) { toast.push(e.message, 'bad') }
  }

  return (
    <Page
      title="Courses and pricing"
      lede="A course is a bundle of modules. Price and access sit here, never on a module."
      actions={<button className="btn btn-pib" onClick={add}>Add a course</button>}
    >
      {naming && (
        <Card
          title="Which modules are in it?"
          actions={
            <>
              <button className="btn btn-quiet" onClick={() => setNaming(null)}>Cancel</button>
              <button className="btn btn-pib" disabled={picked.length === 0} onClick={create}>
                Name it and create
              </button>
            </>
          }
        >
          <ul className="check-list scroll">
            {tree.modules.map((m) => (
              <li key={m.id}>
                <label>
                  <input
                    type="checkbox"
                    checked={picked.includes(m.id)}
                    onChange={(e) => setPicked((v) =>
                      e.target.checked ? [...v, m.id] : v.filter((x) => x !== m.id))}
                  />
                  <span>
                    <b>{m.name}</b> <span className="code">{m.code}</span><br />
                    <span className="text-muted small">
                      {m.chapters.length} chapters &middot; {m.topics} topics
                      {m.emptyChapters > 0 && ` · ${m.emptyChapters} chapters still empty`}
                    </span>
                  </span>
                </label>
              </li>
            ))}
          </ul>
        </Card>
      )}

      {tree.bundles.length === 0 ? (
        <Card>
          <Empty title="No courses yet" action={<button className="btn btn-pib" onClick={add}>New course</button>}>
            Build the modules first, then bundle them.
          </Empty>
        </Card>
      ) : (
        <div className="grid g2">
          {tree.bundles.map((b) => {
            const mods = b.moduleIds
              .map((id) => tree.modules.find((m) => m.id === id))
              .filter(Boolean)
            const chapters = mods.reduce((n, m) => n + m.chapters.length, 0)
            return (
              <div className="card-pib hov" key={b.id} style={{ marginBottom: 0 }}>
                <div className="card-head">
                  <div>
                    <h3>{b.name}</h3>
                    <div className="tiny muted">
                      {b.learners} learners with access &middot; {chapters} chapters
                    </div>
                  </div>
                  <div className="acts">
                    {b.published ? <Tag kind="ok">Live</Tag> : <Tag kind="wait">Draft</Tag>}
                  </div>
                </div>

                <div className="chips" style={{ marginBottom: 14 }}>
                  {mods.map((m) => <span className="chip on" key={m.id}>{m.name}</span>)}
                  {mods.length === 0 && <span className="chip">No modules yet</span>}
                </div>

                <div className="row" style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                  <div>
                    <div className="eyebrow">Open to</div>
                    <div className="chips">
                      {b.access?.premium && <span className="tag tag-premium">Premium</span>}
                      {b.access?.batch && <span className="tag tag-batch">Batch</span>}
                      {!b.access?.premium && !b.access?.batch && <span className="tiny muted">Nobody yet</span>}
                    </div>
                  </div>
                  <div style={{ marginLeft: 'auto', alignSelf: 'flex-end', display: 'flex', gap: 8 }}>
                    {b.readiness.length > 0 && (
                      <span className="tag tag-wait">{b.readiness.length} to finish</span>
                    )}
                    <button className="btn btn-s btn-pib" onClick={() => nav(`/super/courses/${b.id}`)}>
                      Open
                    </button>
                  </div>
                </div>
              </div>
            )
          })}
        </div>
      )}
    </Page>
  )
}
