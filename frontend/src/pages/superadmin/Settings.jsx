import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { useDialog } from '../../components/Dialog'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, Page, Switch, Tabs, Tag } from '../../components/Ui'

const WEEKDAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']

/**
 * The numbers that used to be environment variables or literals.
 *
 * An environment variable is the wrong home for anything the team changes on a Tuesday
 * afternoon: it means a restart and someone with server access. Anything showing "default"
 * has never been set and is running on the value in the code.
 */
export default function Settings() {
  const toast = useToast()
  const { ask } = useDialog()
  const [data, setData] = useState(null)
  const [tab, setTab] = useState(0)
  const [dirty, setDirty] = useState({})
  const [links, setLinks] = useState([])

  const load = async () => {
    setData(await api.get('/super/catalogue/settings'))
    setLinks(await api.get('/super/catalogue/links').catch(() => []))
    setDirty({})
  }
  useEffect(() => { load() }, [])
  if (!data) return <TableSkeleton />

  const valueOf = (s) => (s.key in dirty ? dirty[s.key] : s.value)
  const changed = Object.keys(dirty).length

  const save = async () => {
    try {
      const r = await api.post('/super/catalogue/settings', dirty)
      toast.push(`${r.saved} settings saved.`)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const setVideo = async (s) => {
    const r = await ask({
      title: s.label,
      body: 'Upload to YouTube as unlisted and paste the id. It plays through the same grant '
          + 'flow as a chapter, so the id never reaches the browser until someone presses play.',
      fields: [
        { name: 'title', label: 'Title the learner sees', required: true,
          value: data.settings.find((x) => x.key === 'onboarding.prereqTitle')?.value || '' },
        { name: 'externalId', label: 'Video link', required: true,
          placeholder: 'https://youtu.be/dQw4w9WgXcQ',
          hint: 'A watch link, a share link or the id all work.' }
      ],
      confirmLabel: 'Save video'
    })
    if (!r) return
    try {
      const v = await api.post('/super/catalogue/videos', {
        ref: s.value, title: r.title, externalId: r.externalId, kind: 'PREREQ'
      })
      await api.post('/super/catalogue/settings',
        { [s.key]: v.ref, 'onboarding.prereqTitle': r.title })
      toast.push('Saved. Every new learner sees this before their modules open.')
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const addLink = async () => {
    const r = await ask({
      title: 'Add a community link',
      body: 'Shown to learners after onboarding, so nobody has to ask where the group is.',
      fields: [
        { name: 'label', label: 'Name', required: true, placeholder: 'Premium community' },
        { name: 'url', label: 'Link', required: true },
        { name: 'trackScope', label: 'Shown to', required: true,
          options: [{ value: 'BOTH', label: 'Everyone' }, { value: 'PREMIUM', label: 'Premium' },
                    { value: 'BATCH', label: 'Batch' }] }
      ],
      confirmLabel: 'Add'
    })
    if (!r) return
    await api.post('/super/catalogue/links', { ...r, position: links.length })
    toast.push('Added.')
    await load()
  }

  const groups = [...data.groups, 'Community links']
  const group = groups[Math.min(tab, groups.length - 1)]

  return (
    <Page
      title="App settings"
      lede="Every rule the app runs on, grouped by where it shows up."
      actions={changed > 0
        ? <button className="btn btn-pib" onClick={save}>Save {changed} change{changed > 1 ? 's' : ''}</button>
        : null}
    >
      <Tabs tabs={groups} value={tab} onChange={setTab} />

      {group !== 'Community links' && (
        <Card>
          <table className="table table-pib mb-0">
            <tbody>
              {data.settings.filter((s) => s.group === group).map((s) => (
                <tr key={s.key}>
                  <td style={{ width: '44%' }}>
                    <strong>{s.label}</strong>
                    <div className="tiny muted">{s.note}</div>
                  </td>
                  <td>
                    {s.type === 'video' ? (
                      <div className="d-flex align-items-center gap-2">
                        {s.videoSet
                          ? <Tag kind="ok">set</Tag>
                          : <Tag kind="wait">not set</Tag>}
                        <button className="btn btn-s" onClick={() => setVideo(s)}>
                          {s.videoSet ? 'Replace' : 'Set the video'}
                        </button>
                      </div>
                    ) : s.type === 'toggle' ? (
                      <Switch
                        checked={valueOf(s) === 'true'}
                        label={valueOf(s) === 'true' ? 'On' : 'Off'}
                        onChange={(v) => setDirty((d) => ({ ...d, [s.key]: String(v) }))}
                      />
                    ) : s.type === 'weekday' ? (
                      <select
                        className="form-select"
                        value={valueOf(s)}
                        onChange={(e) => setDirty((d) => ({ ...d, [s.key]: e.target.value }))}
                      >
                        {WEEKDAYS.map((d, i) => (
                          <option key={d} value={String(i + 1)}>{d}</option>
                        ))}
                      </select>
                    ) : (
                      <input
                        className="form-control"
                        type={s.type === 'number' ? 'number' : 'text'}
                        value={valueOf(s)}
                        onChange={(e) => setDirty((d) => ({ ...d, [s.key]: e.target.value }))}
                      />
                    )}
                  </td>
                  <td style={{ width: 100 }} className="text-end">
                    {s.isDefault
                      ? <span className="tiny muted">default</span>
                      : (
                        <button
                          className="btn btn-s"
                          onClick={async () => {
                            await api.post(`/super/catalogue/settings/${s.key}/reset`, {})
                            toast.push('Back to the default.')
                            await load()
                          }}
                        >
                          Reset
                        </button>
                      )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      {group === 'Community links' && (
      <Card
        title="Community links"
        note="Where learners are pointed after onboarding."
        actions={<button className="btn btn-quiet" onClick={addLink}>Add a link</button>}
      >
        {links.length === 0 ? (
          <Empty title="No links yet" icon="resources" />
        ) : (
          <table className="table table-pib mb-0">
            <tbody>
              {links.map((l) => (
                <tr key={l.id}>
                  <td><strong>{l.label}</strong></td>
                  <td className="mono small">{l.url}</td>
                  <td style={{ width: 100 }}>
                    {l.trackScope !== 'BOTH' && <Tag kind="batch">{l.trackScope}</Tag>}
                  </td>
                  <td className="text-end" style={{ width: 90 }}>
                    <button
                      className="btn btn-quiet"
                      onClick={async () => {
                        await api.del(`/super/catalogue/links/${l.id}`)
                        await load()
                      }}
                    >
                      Remove
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
      )}
    </Page>
  )
}
