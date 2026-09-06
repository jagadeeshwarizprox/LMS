import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { useDialog } from '../../components/Dialog'
import Icon from '../../components/Icon'
import { TableSkeleton } from '../../components/Skeletons'
import { Bar, Card, Empty, Page, Stat, Tag } from '../../components/Ui'

const ACCESS = [
  ['premium', 'Premium learners', 'The one to one mentored track'],
  ['batch', 'Batch learners', 'Weekly cohorts'],
  ['selfPaced', 'Self paced', 'No mentor attached'],
  ['sequential', 'Sequential unlock', 'A chapter opens when the one before it is cleared'],
  ['certificate', 'Certificate on completion', 'Issued after the last assignment is approved']
]

/**
 * One course: what is in it, who it opens to, and what is still missing.
 *
 * The readiness list is here rather than on its own page because this is where somebody
 * is deciding whether to publish. A course being half built is a normal Tuesday; not
 * knowing which half is the problem.
 */
export default function CourseDetail() {
  const { id } = useParams()
  const nav = useNavigate()
  const toast = useToast()
  const { ask } = useDialog()
  const [tree, setTree] = useState(null)
  const [picked, setPicked] = useState([])
  const [check, setCheck] = useState(null)

  const load = () => api.get('/super/catalogue').then(setTree)
  useEffect(() => { load() }, [id])
  if (!tree) return <TableSkeleton />

  const b = tree.bundles.find((x) => x.id === id)
  if (!b) return <Empty title="Course not found">It may have been deleted.</Empty>

  const inside = b.moduleIds.map((mid) => tree.modules.find((m) => m.id === mid)).filter(Boolean)
  const outside = tree.modules.filter((m) => !b.moduleIds.includes(m.id))

  const run = async (fn, msg) => {
    try {
      const r = await fn()
      toast.push(r?.reason || msg)
      setPicked([])
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const setModules = (ids) => run(() => api.put(`/super/catalogue/bundles/${id}/modules`, { ids }), 'Course saved.')

  const move = (mid, delta) => {
    const ids = [...b.moduleIds]
    const i = ids.indexOf(mid)
    const j = i + delta
    if (j < 0 || j >= ids.length) return
    ids.splice(j, 0, ids.splice(i, 1)[0])
    setModules(ids)
  }

  const edit = async () => {
    const r = await ask({
      title: `Edit ${b.name}`,
      fields: [
        { name: 'name', label: 'Name', required: true, value: b.name },
        { name: 'tag', label: 'Label on the card', value: b.tag || '' },
        { name: 'description', label: 'One line', value: b.description || '' }
      ],
      confirmLabel: 'Save'
    })
    if (!r) return
    run(() => api.post('/super/catalogue/bundles', {
      ...b, ...r
    }), 'Course saved.')
  }

  const publish = async () => {
    try {
      await api.post(`/super/catalogue/bundles/${id}/publish`, { publish: !b.published })
      toast.push(b.published ? 'Unpublished.' : 'Published.')
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const remove = async () => {
    const ok = await ask({
      title: `Delete ${b.name}?`,
      body: b.learners > 0
        ? `${b.learners} learners are on this course, so it will be archived instead.`
        : 'Nobody is enrolled, so this deletes it.',
      confirmLabel: 'Delete',
      intent: 'danger'
    })
    if (!ok) return
    try {
      const r = await api.del(`/super/catalogue/bundles/${id}`)
      toast.push(r.reason)
      nav('/super/courses')
    } catch (e) { toast.push(e.message, 'bad') }
  }

  /* item four: the chain from a pasted link to a playing video runs through four
     objects, and every one of them can be fine while the learner still sees nothing */
  const runCheck = async () => {
    setCheck('running')
    try { setCheck(await api.get(`/super/catalogue/bundles/${id}/video-check`)) }
    catch (e) { toast.push(e.message, 'bad'); setCheck(null) }
  }

  const chapters = inside.reduce((n, m) => n + m.chapters.length, 0)
  const topics = inside.reduce((n, m) => n + m.topics, 0)
  const minutes = inside.reduce((n, m) => n + m.minutes, 0)

  return (
    <Page
            title={b.name}
      actions={
        <>
          <button className="btn btn-quiet" onClick={() => nav('/super/courses')}>Back</button>
          <button className="btn btn-quiet" onClick={edit}>Edit</button>
          <button className="btn btn-quiet" onClick={runCheck}>Check the videos</button>
          <button className="btn btn-pib" onClick={publish}>{b.published ? 'Unpublish' : 'Publish'}</button>
        </>
      }
    >
      <div className="row g-3 mb-4">
        <div className="col-6 col-md-4 col-xl-2"><Stat value={inside.length} label="Modules" /></div>
        <div className="col-6 col-md-4 col-xl-2"><Stat value={chapters} label="Chapters" /></div>
        <div className="col-6 col-md-4 col-xl-2"><Stat value={topics} label="Topics" /></div>
        <Stat value={`${Math.round(minutes / 60)} h`} label="Runtime" />
        <div className="col-6 col-md-4 col-xl-2"><Stat value={b.learners} label="Enrolled" /></div>
        <div className="col-6 col-md-4 col-xl-2"><Stat value={b.published ? 'Published' : 'Draft'} label="State"
          tone={b.published ? 'ok' : 'warn'} /></div>
      </div>

      {check && (
        <Card
          title="What a learner would hit"
          actions={<button className="btn btn-quiet" onClick={() => setCheck(null)}>Close</button>}
        >
          {check === 'running' ? <p className="text-muted small mb-0">Walking every topic.</p> : (
            <>
              <p className="mb-3">
                <b>{check.playable}</b> of <b>{check.topics}</b> topics will play.
              </p>
              {check.problems.length === 0 ? (
                <p className="text-muted small mb-0">
                  Nothing missing. Every topic has something attached and every video
                  reference resolves.
                </p>
              ) : (
                <ul className="gap-list">
                  {check.problems.map((x, i) => (
                    <li key={i}>
                      <b>{x.where}</b> &middot; {x.detail}
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
        </Card>
      )}

      {b.readiness.length > 0 && (
        <Card title="Before this can be taught" note="None of it blocks you except an empty chapter">
          <ul className="gap-list">
            {b.readiness.map((g, i) => <li key={i}>{g}</li>)}
          </ul>
        </Card>
      )}

      <div className="row g-3">
        <div className="col-lg-7">
        <Card title="Modules in this course">
          {inside.length === 0 ? (
            <Empty title="No modules yet">Tick modules on the right and add them.</Empty>
          ) : (
            <table className="table table-pib mb-0">
              <tbody>
                {inside.map((m, i) => (
                  <tr key={m.id}>
                    <td style={{ width: 46 }}><span className="code">{String(i + 1).padStart(2, '0')}</span></td>
                    <td>
                      <div className="fw-semibold">{m.name}</div>
                      <div className="text-muted small">{m.chapters.length} chapters &middot; {m.topics} topics</div>
                    </td>
                    <td style={{ width: 110 }}>
                      <Bar done={m.chapters.length - m.emptyChapters} total={m.chapters.length} id={m.id} />
                    </td>
                    <td className="d-flex gap-2 align-items-center flex-wrap" style={{ width: 180 }}>
                      <button className="icon-btn" aria-label="Move up" onClick={() => move(m.id, -1)}>
                        <Icon name="up" />
                      </button>
                      <button className="icon-btn" aria-label="Move down" onClick={() => move(m.id, 1)}>
                        <Icon name="down" />
                      </button>
                      <button className="btn btn-quiet btn-sm" onClick={() => nav(`/super/modules/${m.id}`)}>Open</button>
                      <button className="btn btn-danger btn-sm"
                        onClick={() => setModules(b.moduleIds.filter((x) => x !== m.id))}>Remove</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>

        </div>
        <div className="col-lg-5">
        <Card
          title="Add modules"
          actions={
            <button className="btn btn-pib btn-sm" disabled={picked.length === 0}
              onClick={() => setModules([...b.moduleIds, ...picked])}>
              Add {picked.length || ''}
            </button>
          }
        >
          {outside.length === 0 ? (
            <Empty title="Every module is already in this course" />
          ) : (
            <ul className="check-list scroll">
              {outside.map((m) => (
                <li key={m.id}>
                  <label>
                    <input
                      type="checkbox"
                      checked={picked.includes(m.id)}
                      onChange={(e) => setPicked((p) =>
                        e.target.checked ? [...p, m.id] : p.filter((x) => x !== m.id))}
                    />
                    <span>
                      <b>{m.name}</b> <span className="code">{m.code}</span><br />
                      <span className="text-muted small">{m.chapters.length} chapters &middot; {m.topics} topics</span>
                    </span>
                  </label>
                </li>
              ))}
            </ul>
          )}
        </Card>
        </div>
      </div>

      <Card title="Who this course opens to">
        <ul className="check-list">
          {ACCESS.map(([k, label, note]) => (
            <li key={k}>
              <label>
                <input
                  type="checkbox"
                  checked={!!b.access?.[k]}
                  onChange={(e) => run(
                    () => api.put(`/super/catalogue/bundles/${id}/access`,
                      { ...b.access, [k]: e.target.checked }),
                    'Access updated.')}
                />
                <span><b>{label}</b><br />{note}</span>
              </label>
            </li>
          ))}
        </ul>
      </Card>

      <Card title="Danger zone">
        <p className="text-muted small">
          A course with learners on it is archived rather than deleted.
        </p>
        <button className="btn btn-danger" onClick={remove}>Delete this course</button>
      </Card>
    </Page>
  )
}
