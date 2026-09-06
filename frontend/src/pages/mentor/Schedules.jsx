import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { useDialog } from '../../components/Dialog'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, LoadError, Page, Tag } from '../../components/Ui'

const DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']
const KINDS = [
  ['GROUP_DOUBT', 'Group doubt clearing'], ['DOUBT', 'One to one doubt clearing'],
  ['LIVE', 'Live session'], ['PROJECT', 'Project session'],
  ['ONBOARDING', 'Onboarding call'], ['INDUCTION', 'Induction']
]

/**
 * A cadence that keeps itself. Slots are generated two weeks ahead from these, so a
 * weekly session never quietly stops happening because nobody created it.
 *
 * This lists every repeating session in the org, not only the viewer's, because a mentor
 * setting up Thursday needs to see that Thursday is already taken. The server marks each
 * row `mine` and `canEdit`; the ones that are not yours are here to be read.
 */
export default function Schedules() {
  const toast = useToast()
  const { ask } = useDialog()
  const [rows, setRows] = useState(null)

  const [loadError, setLoadError] = useState(null)

  const load = () => api.get('/week/schedules')
    .then((r) => { setRows(r); setLoadError(null) })
    .catch((e) => setLoadError(e.message))
  useEffect(() => { load() }, [])
  if (loadError) {
    return <Page title="Repeating sessions"><LoadError error={loadError} onRetry={load} /></Page>
  }
  if (!rows) return <TableSkeleton />

  const add = async () => {
    const r = await ask({
      title: 'Add a repeating session',
      body: 'Slots are generated two weeks ahead and refreshed nightly.',
      fields: [
        { name: 'label', label: 'Name', required: true, placeholder: 'Monday doubt clearing' },
        { name: 'kind', label: 'Kind', required: true,
          options: KINDS.map(([v, l]) => ({ value: v, label: l })) },
        { name: 'weekday', label: 'Day', required: true,
          options: DAYS.map((d, i) => ({ value: String(i + 1), label: d })) },
        { name: 'startTime', label: 'Start time', type: 'time', required: true, value: '19:00' },
        { name: 'durationMin', label: 'Minutes', type: 'number', value: '60' },
        { name: 'capacity', label: 'Seats', type: 'number', value: '80' },
        { name: 'trackScope', label: 'For', required: true,
          options: [{ value: 'BATCH', label: 'Batch' }, { value: 'PREMIUM', label: 'Premium' },
                    { value: 'BOTH', label: 'Both' }] }
      ],
      confirmLabel: 'Add'
    })
    if (!r) return
    await api.post('/week/schedules', {
      label: r.label, kind: r.kind, weekday: Number(r.weekday), startTime: r.startTime,
      durationMin: Number(r.durationMin || 60), capacity: Number(r.capacity || 80),
      trackScope: r.trackScope
    })
    toast.push('Added. The next two weeks of slots are live.')
    await load()
  }

  const toggle = async (s) => {
    await api.post(`/week/schedules/${s.id}/active`, { active: !s.active })
    await load()
  }

  return (
    <Page title="Repeating sessions"
      lede="The cadence behind the week. Yours are marked; the rest are here so you can see what is already taken."
      actions={rows.some((r) => r.canEdit) || rows.length === 0
        ? <button className="btn btn-pib" onClick={add}>Add a repeating session</button>
        : null}>
      <Card note="Every slot used to be created by hand, which is how a weekly cadence quietly stops happening.">
        {rows.length === 0 ? (
          <Empty title="Nothing repeating yet" icon="slots">
            Add your doubt clearing and project sessions once, and they keep themselves.
          </Empty>
        ) : (
          <table className="table table-pib mb-0">
            <thead>
              <tr><th>Session</th><th>When</th><th>Host</th><th>For</th><th>Seats</th>
                <th>Upcoming</th><th /></tr>
            </thead>
            <tbody>
              {rows.map((s) => (
                <tr key={s.id}>
                  <td>
                    <strong>{s.label || s.kind}</strong>
                    <div className="small muted">{KINDS.find(([v]) => v === s.kind)?.[1] || s.kind}</div>
                  </td>
                  <td className="mono">{s.weekdayName}, {s.startTime}</td>
                  <td>{s.mine ? <Tag kind="ok">Yours</Tag> : (s.owner || 'Rotating')}</td>
                  <td>{s.trackScope}</td>
                  <td className="mono">{s.capacity}</td>
                  <td className="mono">{s.upcoming}</td>
                  <td className="text-end">
                    <Tag kind={s.active ? 'ok' : 'batch'}>{s.active ? 'Running' : 'Paused'}</Tag>
                    {s.canEdit && (
                      <button className="btn btn-quiet ms-2" onClick={() => toggle(s)}>
                        {s.active ? 'Pause' : 'Resume'}
                      </button>
                    )}
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
