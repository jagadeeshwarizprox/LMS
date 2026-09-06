import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { useDialog } from '../../components/Dialog'
import { parseVideo } from '../../components/VideoField'
import FileUpload from '../../components/FileUpload'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, Page, Stat, Tag, fmtDate } from '../../components/Ui'

const KIND = {
  DOUBT: 'Doubt clearing', RECAP: 'Recap', PROJECT: 'Project session',
  INDUSTRY: 'Industry session', INDUCTION: 'Induction', MOCK_DEBRIEF: 'Mock debrief'
}

/**
 * Sessions that have finished and have no recording.
 *
 * This queue is the point of the whole feature. A publishing form only fills a library
 * when somebody remembers; a queue that ages in days makes the missing week visible,
 * the same way an unreviewed task does.
 */
export default function Recordings() {
  const toast = useToast()
  const { ask } = useDialog()
  const [rows, setRows] = useState(null)
  const [modules, setTopics] = useState([])
  /* slides and datasets, attached per session before it is published */
  const [materials, setMaterials] = useState({})
  const [attaching, setAttaching] = useState(null)

  const load = async () => {
    setRows(await api.get('/mentor/recordings/pending'))
    setTopics(await api.get('/super/catalogue').then((t) => t.modules).catch(() => []))
  }
  useEffect(() => { load() }, [])
  if (!rows) return <TableSkeleton />

  const publish = async (s) => {
    const isRecap = s.recordingKind === 'RECAP'
    const r = await ask({
      title: 'Publish the recording',
      body: `${KIND[s.recordingKind]} held ${fmtDate(s.heldOn)}. The date, length and who `
          + 'attended come from the session itself, so this is just the video.',
      fields: [
        { name: 'title', label: 'Title', required: true, value: s.suggestedTitle },
        { name: 'link', label: 'Recording link', required: true,
          placeholder: 'Paste the YouTube or Zoom cloud link',
          hint: 'Unlisted on YouTube, or a Zoom cloud recording link.' },
        { name: 'kind', label: 'Kind', required: true, value: s.recordingKind,
          options: Object.entries(KIND).map(([v, l]) => ({ value: v, label: l })) },
        { name: 'moduleId', label: 'Subject it covers', value: '',
          options: modules.map((t) => ({ value: t.id, label: t.name })),
          hint: 'A recap belongs to a subject. Doubt clearing usually does not.' },
        { name: 'seriesLabel', label: 'Part of a series', value: '',
          placeholder: isRecap ? 'The subject and month' : 'Leave empty unless it runs over days',
          hint: 'A recap over two or three days is one series. Name it the same each day.' },
        { name: 'partNumber', label: 'Which day', type: 'number', value: '' },
        { name: 'coveredSummary', label: 'What was covered', multiline: true,
          hint: 'One paragraph. This is what makes a two hour video findable.' }
      ],
      confirmLabel: 'Publish'
    })
    if (!r) return
    if (!parseVideo(r.link, 'YOUTUBE').id && !r.link.startsWith('http')) {
      toast.push('That link is not one we can play.', 'bad')
      return
    }
    try {
      await api.post('/mentor/recordings', {
        materialIds: (materials[s.slotId] || []).map((f) => f.id),
        slotId: s.slotId,
        kind: r.kind,
        title: r.title,
        videoLink: r.link,
        moduleId: r.moduleId || null,
        seriesLabel: r.seriesLabel || null,
        partNumber: r.partNumber ? Number(r.partNumber) : null,
        coveredSummary: r.coveredSummary || null
      })
      setMaterials((m) => { const n = { ...m }; delete n[s.slotId]; return n })
      toast.push('Published. Everyone who can see it has it now.')
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const overdue = rows.filter((r) => r.overdue).length

  return (
    <Page
      title="Sessions to publish"
      lede="Sessions you have run that still need their recording published."
      actions={overdue > 0 ? <Tag kind="stop">{overdue} over a week</Tag> : null}
    >
      <div className="row g-3 mb-4">
        <div className="col-6 col-lg-4"><Stat value={rows.length} label="Waiting to publish" icon="recordings" /></div>
        <div className="col-6 col-lg-4">
          <Stat value={overdue} label="Over a week old" icon="risk"
            tone={overdue ? 'var(--stop)' : undefined} />
        </div>
        <div className="col-12 col-lg-4">
          <Stat value={rows.filter((r) => r.attended > 0).length} label="With attendance marked" icon="batch" />
        </div>
      </div>

      <Card note="A session with no recording is a gap in the library that nobody notices unless it is counted.">
        {rows.length === 0 ? (
          <Empty title="Everything is published" icon="check">
            Every session that has finished has its recording up.
          </Empty>
        ) : (
          <table className="table table-pib stacked mb-0">
            <thead>
              <tr><th>Session</th><th>Held</th><th>Length</th><th>Attended</th>
                <th>Waiting</th><th /></tr>
            </thead>
            <tbody>
              {rows.map((s) => (
                <tr key={s.slotId}>
                  <td data-label="Session">
                    <strong>{s.suggestedTitle}</strong>
                    <div className="small muted">
                      {KIND[s.recordingKind]}{s.batch ? ` · ${s.batch}` : ''}
                    </div>
                  </td>
                  <td data-label="Held" className="mono">{fmtDate(s.heldOn)}</td>
                  <td data-label="Length" className="mono">{s.durationMin}m</td>
                  <td data-label="Attended" className="mono">{s.attended || '\u2014'}</td>
                  <td data-label="Waiting">
                    {s.overdue
                      ? <Tag kind="stop">{s.daysWaiting}d</Tag>
                      : <span className="mono">{s.daysWaiting}d</span>}
                  </td>
                  <td className="text-end d-flex gap-2 justify-content-end">
                    <button
                      className="btn btn-quiet"
                      onClick={() => setAttaching(attaching === s.slotId ? null : s.slotId)}
                    >
                      {materials[s.slotId]?.length
                        ? `${materials[s.slotId].length} attached`
                        : 'Add materials'}
                    </button>
                    <button className="btn btn-pib" onClick={() => publish(s)}>Publish</button>
                  </td>
                </tr>
              ))}
              {rows.filter((s) => attaching === s.slotId).map((s) => (
                <tr key={`${s.slotId}-files`} className="row-expand">
                  <td colSpan={6}>
                    {/*
                      * A recording on its own is half a session. The slides, the dataset
                      * and the notebook that were on screen are what make it useful a
                      * month later, and until now there was nowhere to put them.
                      */}
                    <FileUpload
                      purpose="RECORDING_NOTE"
                      linkedId={s.slotId}
                      label="Slides, datasets and notes from this session"
                      onAllUploaded={(fs) => setMaterials((m) => ({
                        ...m, [s.slotId]: [...(m[s.slotId] || []), ...fs]
                      }))}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </Page>
  )
}
