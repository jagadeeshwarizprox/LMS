import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { useDialog } from '../../components/Dialog'
import { TableSkeleton } from '../../components/Skeletons'
import Icon from '../../components/Icon'
import { Empty, Grid, LoadError, Page, Stat } from '../../components/Ui'

/**
 * The content library as a tree, because the shape is the point.
 *
 * A module holds chapters. A chapter holds topics, and carries one test, one
 * assignment and one teach back covering all of them. Opening a module shows its
 * chapters with three pips saying what is built and what is not, which is the whole
 * answer to "is this ready to teach".
 */
export default function Modules() {
  const toast = useToast()
  const { ask } = useDialog()
  const nav = useNavigate()
  const [tree, setTree] = useState(null)
  const [q, setQ] = useState('')
  const [open, setOpen] = useState(0)

  const [loadError, setLoadError] = useState(null)

  const load = () => api.get('/super/catalogue')
    .then((r) => { setTree(r); setLoadError(null) })
    .catch((e) => setLoadError(e.message))
  useEffect(() => { load() }, [])
  if (loadError) {
    return <Page title="Modules and chapters"><LoadError error={loadError} onRetry={load} /></Page>
  }
  if (!tree) return <TableSkeleton />

  const add = async () => {
    const r = await ask({
      title: 'New module',
      body: 'A module is one subject. Chapters go inside it, and topics inside those.',
      fields: [
        { name: 'name', label: 'Name', required: true, placeholder: 'What this module is called' },
        { name: 'description', label: 'One line', placeholder: 'What it covers' }
      ],
      confirmLabel: 'Create module'
    })
    if (!r) return
    try {
      const saved = await api.post('/super/catalogue/modules', r)
      toast.push('Module created.')
      nav(`/super/modules/${saved.id}`)
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const addChapter = async (m) => {
    const r = await ask({
      title: `New chapter in ${m.name}`,
      body: 'One sitting. Its topics are the videos; the test, assignment and teach back cover them together.',
      fields: [{ name: 'title', label: 'Title', required: true }],
      confirmLabel: 'Create chapter'
    })
    if (!r) return
    try {
      const saved = await api.post('/super/catalogue/chapters', { ...r, moduleId: m.id })
      toast.push('Chapter created.')
      nav(`/super/chapters/${saved.id}`)
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const needle = q.trim().toLowerCase()
  const rows = tree.modules.filter((m) => !needle || m.name.toLowerCase().includes(needle)
    || (m.code || '').toLowerCase().includes(needle))

  const chapters = tree.modules.reduce((a, m) => a + m.chapters.length, 0)
  const topics = tree.modules.reduce((a, m) => a + (m.topics || 0), 0)
  const empty = tree.modules.reduce((a, m) => a + (m.emptyChapters || 0), 0)
  const tests = tree.modules.reduce(
    (a, m) => a + m.chapters.filter((c) => c.test?.enabled).length, 0)

  return (
    <Page
      title="Modules and chapters"
      lede={'A module holds chapters. A chapter holds topics, and carries one test, one '
        + 'assignment and one teach back for all of them.'}
      actions={(
        <>
          <input
            className="form-control form-control-sm"
            style={{ width: 190 }}
            placeholder="Find a module"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            aria-label="Find a module"
          />
          <button className="btn btn-pib" onClick={add}>Add a module</button>
        </>
      )}
    >
      <Grid cols={3} style={{ marginBottom: 18 }}>
        <Stat
          value={tree.modules.length} label="Modules" icon="catalogue"
          hint={`across ${tree.bundles?.length || 0} courses`}
        />
        <Stat
          value={chapters} label="Chapters" icon="library"
          hint={empty ? `${empty} with no topics` : 'all have topics'}
        />
        <Stat value={topics} label="Topics" icon="recordings" hint="video and files sit here" />
        <Stat
          value={tests} label="Chapter tests" icon="projects"
          hint={`${Math.max(0, chapters - tests)} chapters with no test`}
        />
      </Grid>

      {rows.length === 0 ? (
        <Empty title={needle ? 'Nothing matches that' : 'No modules yet'}>
          {needle ? 'Try a shorter search.' : 'Create the first module, then paste its outline in.'}
        </Empty>
      ) : rows.map((m, i) => {
        const gaps = m.chapters.filter((c) => !c.test?.enabled).length
        const isOpen = open === i
        return (
          <div className="tree" key={m.id}>
            <button className="tree-head" onClick={() => setOpen(isOpen ? -1 : i)}>
              <span className="tree-idx">{m.code || `M${i + 1}`}</span>
              <span className="tree-name">{m.name}</span>
              <span className="tree-meta">
                {!m.active
                  ? <span className="tag tag-wait">Archived</span>
                  : gaps > 0
                    ? <span className="tag tag-wait">{gaps} chapter{gaps > 1 ? 's' : ''} with no test</span>
                    : <span className="tag tag-ok">complete</span>}
                <span className="mono tiny">
                  {m.chapters.length} chapter{m.chapters.length === 1 ? '' : 's'} &middot; {m.topics} topics
                </span>
                <span className={`chev ${isOpen ? 'open' : ''}`}><Icon name="chevron" size={16} /></span>
              </span>
            </button>

            {isOpen && (
              <div className="tree-kids">
                {m.chapters.map((c, j) => (
                  <button className="kid" key={c.id} onClick={() => nav(`/super/chapters/${c.id}`)}>
                    <span className="mono tiny" style={{ color: 'var(--ink-30)' }}>C{j + 1}</span>
                    <span style={{ fontWeight: 500 }}>{c.title}</span>
                    <span className="tiny muted">{c.topics.length} topics</span>
                    <span className="km">
                      <span className="tiny muted">test</span>
                      <span className={`pip ${c.test?.enabled ? (c.questions ? 'on' : 'part') : ''}`} />
                      <span className="tiny muted">task</span>
                      <span className={`pip ${c.hasAssignment ? 'on' : ''}`} />
                      <span className="tiny muted">teach back</span>
                      <span className={`pip ${c.teachback?.enabled ? 'on' : ''}`} />
                      <span
                        className="tiny"
                        style={{ color: 'var(--blue-500)', fontWeight: 600, marginLeft: 8 }}
                      >
                        Open
                      </span>
                    </span>
                  </button>
                ))}
                <button
                  className="kid"
                  style={{ color: 'var(--ink-30)' }}
                  onClick={(e) => { e.stopPropagation(); addChapter(m) }}
                >
                  + Add a chapter to {m.name}
                </button>
              </div>
            )}
          </div>
        )
      })}
    </Page>
  )
}
