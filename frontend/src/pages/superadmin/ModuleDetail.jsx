import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { useDialog } from '../../components/Dialog'
import Icon from '../../components/Icon'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, Page, Stat, Tag } from '../../components/Ui'

/**
 * One module, its chapters open as an accordion.
 *
 * The accordion is the point. A chapter is only interesting for what is inside it, and
 * a list that makes you navigate away to find out has you clicking back and forth to
 * answer "which chapter is missing its topics".
 */
export default function ModuleDetail() {
  const { id } = useParams()
  const nav = useNavigate()
  const toast = useToast()
  const { ask } = useDialog()
  const [tree, setTree] = useState(null)
  const [open, setOpen] = useState({})

  const load = () => api.get('/super/catalogue').then(setTree)
  useEffect(() => { load() }, [id])
  if (!tree) return <TableSkeleton />

  const m = tree.modules.find((x) => x.id === id)
  if (!m) return <Empty title="Module not found">It may have been deleted.</Empty>

  const run = async (fn, msg) => {
    try {
      const r = await fn()
      toast.push(r?.reason || msg)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const rename = async () => {
    const r = await ask({
      title: `Edit ${m.name}`,
      fields: [
        { name: 'name', label: 'Name', required: true, value: m.name },
        { name: 'code', label: 'Code', value: m.code || '' },
        { name: 'description', label: 'One line', value: m.description || '' }
      ],
      confirmLabel: 'Save'
    })
    if (!r) return
    run(() => api.post('/super/catalogue/modules', { ...r, id: m.id, slug: m.slug }), 'Module saved.')
  }

  const addChapter = async () => {
    const r = await ask({
      title: 'New chapter',
      body: 'A chapter is one sitting. Its topics are the videos; its test, assignment and teach back cover all of them together.',
      fields: [
        { name: 'title', label: 'Title', required: true },
        { name: 'summary', label: 'One line', placeholder: 'What this chapter covers' }
      ],
      confirmLabel: 'Add chapter'
    })
    if (!r) return
    run(() => api.post('/super/catalogue/chapters', { ...r, moduleId: m.id }), 'Chapter added.')
  }

  const pasteOutline = async () => {
    const r = await ask({
      title: 'Paste an outline',
      body: 'One chapter per line. Numbering and bullets are stripped, and titles already here are skipped, so pasting twice is safe.',
      fields: [{ name: 'text', label: 'Outline', multiline: true, required: true }],
      confirmLabel: 'Add them'
    })
    if (!r) return
    try {
      const out = await api.post(`/super/catalogue/modules/${m.id}/chapters/bulk`, { text: r.text })
      toast.push(`${out.added} added, ${out.skipped} already there.`)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const move = (chapterId, delta) => {
    const ids = m.chapters.map((c) => c.id)
    const i = ids.indexOf(chapterId)
    const j = i + delta
    if (j < 0 || j >= ids.length) return
    ids.splice(j, 0, ids.splice(i, 1)[0])
    run(() => api.post(`/super/catalogue/modules/${m.id}/chapters/reorder`, { ids }), 'Order saved.')
  }

  const removeChapter = async (c) => {
    const ok = await ask({
      title: `Delete ${c.title}?`,
      body: c.studied > 0
        ? `${c.studied} learners have progress on this chapter, so this will be refused.`
        : 'Its topics and test questions go with it.',
      confirmLabel: 'Delete',
      intent: 'danger'
    })
    if (!ok) return
    run(() => api.del(`/super/catalogue/chapters/${c.id}`), 'Chapter deleted.')
  }

  const removeModule = async () => {
    const ok = await ask({
      title: `Delete ${m.name}?`,
      body: 'A module anyone has studied is archived instead, and stays readable for them.',
      confirmLabel: 'Delete',
      intent: 'danger'
    })
    if (!ok) return
    try {
      const r = await api.del(`/super/catalogue/modules/${m.id}`)
      toast.push(r.reason)
      nav('/super/modules')
    } catch (e) { toast.push(e.message, 'bad') }
  }

  /*
   * What "ready" means for a chapter: it has topics, every topic has a video, and any
   * test or task it switched on has actually been written. Four things, so four dots.
   */
  const parts = (c) => [
    [c.topics.length > 0, 'has topics'],
    [c.topics.length > 0 && c.topics.every((t) => t.hasVideo), 'every topic has a video'],
    [!c.test.enabled || c.questions > 0, 'test written'],
    [!c.assignment?.enabled || !!c.assignment?.title, 'task written']
  ]
  const dot = (on) => `ready-dot${on ? ' is-on' : ''}`
  const readyText = (c) => parts(c).map(([on, what]) => `${on ? '\u2713' : '\u2717'} ${what}`).join(', ')
  const ready = m.chapters.filter((c) => parts(c).every(([on]) => on)).length

  return (
    <Page
      eyebrow={`${m.code || 'Module'} \u00b7 content library`}
      title={m.name}
      actions={
        <>
          <button className="btn btn-quiet" onClick={() => nav('/super/modules')}>Back</button>
          <button className="btn btn-quiet" onClick={rename}>Edit</button>
          <button className="btn btn-quiet" onClick={pasteOutline}>Paste an outline</button>
          <button className="btn btn-pib" onClick={addChapter}>New chapter</button>
        </>
      }
    >
      <div className="row g-3 mb-4">
        <div className="col-6 col-md-4 col-xl-2"><Stat value={m.chapters.length} label="Chapters" /></div>
        <div className="col-6 col-md-4 col-xl-2"><Stat value={m.topics} label="Topics" /></div>
        <div className="col-6 col-md-4 col-xl-2"><Stat value={m.assignments} label="Assignments" /></div>
        <div className="col-6 col-md-4 col-xl-2"><Stat value={m.files} label="Files" /></div>
        {/* runtime is gone with the topic minutes field: it was the sum of a number
            somebody typed in by hand, so it read as fact and was a guess */}
        <div className="col-6 col-md-4 col-xl-2">
          <Stat value={`${ready}/${m.chapters.length}`} label="Chapters ready"
            tone={ready === m.chapters.length && ready > 0 ? 'var(--teal-600)' : undefined} />
        </div>
      </div>

      <Card title="Chapters">
        {m.chapters.length === 0 ? (
          <Empty title="No chapters yet" action={<button className="btn btn-pib" onClick={pasteOutline}>Paste an outline</button>}>
            Paste the outline in one go, then fill each chapter in.
          </Empty>
        ) : (
          <ul className="accordion">
            {m.chapters.map((c, i) => (
              <li key={c.id} className={open[c.id] ? 'open' : ''}>
                <div className="acc-head">
                  <button
                    className="acc-toggle"
                    aria-expanded={!!open[c.id]}
                    onClick={() => setOpen((o) => ({ ...o, [c.id]: !o[c.id] }))}
                  >
                    <span className="code">{String(i + 1).padStart(2, '0')}</span>
                    <span className="acc-title">{c.title}</span>
                    <span className="acc-meta">
                      {c.topics.length} topic{c.topics.length === 1 ? '' : 's'}
                      {c.questions > 0 && ` \u00b7 ${c.questions} questions`}
                    </span>
                  </button>
                  {/*
                    * What is finished, as four dots rather than a sentence.
                    *
                    * The state of a chapter was only visible by opening it, so building a
                    * module meant clicking through every one to find the half done ones.
                    * A column of pale dots now says where the work is without reading.
                    */}
                  <span className="ready-dots" title={readyText(c)}>
                    <i className={dot(c.topics.length > 0)} />
                    <i className={dot(c.topics.every((t) => t.hasVideo) && c.topics.length > 0)} />
                    <i className={dot(!c.test.enabled || c.questions > 0)} />
                    <i className={dot(!c.assignment?.enabled || !!c.assignment?.title)} />
                  </span>
                  <span className="acc-tags">
                    {c.studied > 0 && <Tag kind="ok">{c.studied} studying</Tag>}
                  </span>
                  <span className="acc-actions">
                    <button className="icon-btn" aria-label="Move up" onClick={() => move(c.id, -1)}>
                      <Icon name="up" />
                    </button>
                    <button className="icon-btn" aria-label="Move down" onClick={() => move(c.id, 1)}>
                      <Icon name="down" />
                    </button>
                    <button className="btn btn-quiet btn-sm" onClick={() => nav(`/super/chapters/${c.id}`)}>Open</button>
                    <button className="btn btn-danger btn-sm" onClick={() => removeChapter(c)}>Delete</button>
                  </span>
                </div>
                {open[c.id] && (
                  <div className="acc-body">
                    {c.topics.length === 0 ? (
                      <p className="text-muted small">Nothing inside this chapter yet.</p>
                    ) : (
                      <table className="table table-pib mb-0">
                        <tbody>
                          {c.topics.map((t, ti) => (
                            <tr key={t.id}>
                              <td style={{ width: 62 }}><span className="code">T{i + 1}.{ti + 1}</span></td>
                              <td className="fw-semibold">{t.title}</td>
                              <td style={{ width: 120 }}>
                                {t.hasVideo ? <Tag kind="ok">Video set</Tag> : <Tag kind="wait">No video</Tag>}
                              </td>
                              <td className="text-end" style={{ width: 90 }}>{t.resources} files</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    )}
                    <div className="acc-foot">
                      <button className="btn btn-quiet btn-sm" onClick={() => nav(`/super/chapters/${c.id}`)}>
                        Edit this chapter
                      </button>
                    </div>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card title="Danger zone">
        <p className="text-muted small">
          A module with progress, or one in a course, is archived rather than deleted.
        </p>
        <button className="btn btn-danger" onClick={removeModule}>Delete this module</button>
      </Card>
    </Page>
  )
}
