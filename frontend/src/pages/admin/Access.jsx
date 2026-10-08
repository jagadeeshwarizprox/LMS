import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { useDialog } from '../../components/Dialog'
import { parseVideo } from '../../components/VideoField'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, LoadError, Page, Stat, Tabs, Tag, fmtDateTime } from '../../components/Ui'

const TABS = ['Sharing flags', 'Playback log', 'Video library']

const FLAG_LABEL = {
  MANY_IPS: 'Many networks',
  TOKEN_BURST: 'Burst of plays',
  DEVICE_CHURN: 'Many devices',
  IMPOSSIBLE_TRAVEL: 'Sign ins fighting'
}

/**
 * Access and sharing, in one place.
 *
 * The server has watched for account sharing from the start: every play is granted
 * and logged, a nightly sweep raises flags, a leaked video can be rotated, and an
 * account can be suspended. None of it had a screen, so the office only found out
 * about sharing when somebody told them. This is that screen.
 */
export default function Access() {
  const toast = useToast()
  const { ask } = useDialog()
  const [tab, setTab] = useState(0)
  const [flags, setFlags] = useState(null)
  const [grants, setGrants] = useState(null)
  const [videos, setVideos] = useState(null)
  const [error, setError] = useState(null)
  const [q, setQ] = useState('')
  const [busy, setBusy] = useState(false)

  const loadFlags = () => api.get('/admin/access/flags').then(setFlags)
  const loadGrants = () => api.get('/admin/access/grants').then(setGrants)
  const loadVideos = () => api.get('/admin/access/videos').then(setVideos)

  const loadAll = () => {
    setError(null)
    Promise.all([loadFlags(), loadGrants(), loadVideos()]).catch((e) => setError(e.message))
  }
  useEffect(() => { loadAll() }, [])

  const shownGrants = useMemo(() => {
    if (!grants) return []
    const n = q.trim().toLowerCase()
    if (!n) return grants
    return grants.filter((g) => [g.name, g.email, g.video, g.ip, g.outcome]
      .some((x) => String(x || '').toLowerCase().includes(n)))
  }, [grants, q])

  if (error) return <Page title="Access and sharing"><LoadError error={error} onRetry={loadAll} /></Page>
  if (!flags || !grants || !videos) return <TableSkeleton />

  const sweep = async () => {
    setBusy(true)
    try {
      const r = await api.post('/admin/access/sweep', {})
      toast.push(r.raised ? `${r.raised} new flag${r.raised === 1 ? '' : 's'} raised.` : 'Nothing new found.')
      await loadFlags()
    } catch (e) { toast.push(e.message, 'bad') } finally { setBusy(false) }
  }

  const clear = async (f) => {
    const ok = await ask({
      title: 'Clear this flag?',
      body: 'Use this once you have looked into it. Clearing is logged, and the sweep raises '
        + 'it again if the pattern carries on.',
      confirmLabel: 'Clear flag'
    })
    if (!ok) return
    try {
      await api.post(`/admin/access/flags/${f.id}/clear`, {})
      toast.push('Flag cleared.')
      await loadFlags()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const suspend = async (userId, name, currentlySuspended) => {
    if (currentlySuspended) {
      const ok = await ask({
        title: `Restore ${name || 'this account'}?`,
        body: 'They can sign in again straight away.',
        confirmLabel: 'Restore'
      })
      if (!ok) return
      try {
        await api.post(`/admin/access/users/${userId}/suspend`, { suspended: false })
        toast.push('Account restored.')
        await loadFlags()
      } catch (e) { toast.push(e.message, 'bad') }
      return
    }
    const r = await ask({
      title: `Suspend ${name || 'this account'}?`,
      intent: 'danger',
      body: 'Every session they have ends now and they cannot sign in until restored. '
        + 'Nothing on their record is changed.',
      fields: [{ name: 'reason', label: 'Reason, kept on the record', required: true }],
      confirmLabel: 'Suspend'
    })
    if (!r) return
    try {
      await api.post(`/admin/access/users/${userId}/suspend`, { suspended: true, reason: r.reason })
      toast.push('Suspended. Their sessions have ended.')
      await loadFlags()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const rotate = async (v) => {
    const r = await ask({
      title: `Rotate "${v.title || 'this video'}"?`,
      intent: 'danger',
      body: 'For a link that has leaked. Upload the video again, paste the new link here, and '
        + 'every chapter and recording that uses it follows. The old link stops being handed out.',
      fields: [{
        name: 'link',
        label: 'New video link',
        required: true,
        validate: (val) => (val && !parseVideo(val, v.provider).id ? parseVideo(val, v.provider).note : null)
      }],
      confirmLabel: 'Rotate'
    })
    if (!r) return
    try {
      const { id } = parseVideo(r.link, v.provider)
      await api.post(`/admin/access/videos/${v.id}/rotate`, { externalId: id || r.link })
      toast.push('Rotated. New plays use the new video.')
      await loadVideos()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const refused = grants.filter((g) => g.outcome === 'REFUSED').length
  const suspendedNow = flags.filter((f) => f.suspended).length

  return (
    <Page
      title="Access and sharing"
      lede="Who is watching, from where, and whether an account looks shared."
      actions={<button className="btn btn-pib" onClick={sweep} disabled={busy}>Run the check now</button>}
    >
      <div className="row g-3 mb-3">
        <div className="col-6 col-lg-3"><Stat value={flags.length} label="Open flags" icon="risk" tone={flags.length ? 'var(--stop)' : undefined} /></div>
        <div className="col-6 col-lg-3"><Stat value={suspendedNow} label="Flagged and suspended" icon="ban" /></div>
        <div className="col-6 col-lg-3"><Stat value={grants.length} label="Recent plays" icon="recordings" /></div>
        <div className="col-6 col-lg-3"><Stat value={refused} label="Plays refused" icon="shield" /></div>
      </div>

      <Tabs tabs={TABS} value={tab} onChange={setTab} />

      {tab === 0 && (
        <Card
          title="Sharing flags"
          note="Raised by the nightly sweep. Look into each one, then clear it or suspend the account."
        >
          {flags.length === 0 ? (
            <Empty title="No open flags">Nothing looks shared right now.</Empty>
          ) : (
            <div className="table-responsive">
              <table className="table table-pib mb-0">
                <thead><tr><th>Raised</th><th>Who</th><th>What</th><th>Detail</th><th /></tr></thead>
                <tbody>
                  {flags.map((f) => (
                    <tr key={f.id}>
                      <td className="mono">{fmtDateTime(f.createdAt)}</td>
                      <td>
                        {f.name || 'Unknown'}
                        {f.suspended && <> <Tag kind="stop">Suspended</Tag></>}
                      </td>
                      <td><Tag kind="wait">{FLAG_LABEL[f.kind] || f.kind}</Tag></td>
                      <td className="small">{f.detail}</td>
                      <td className="text-end">
                        <div className="row-actions">
                          <button className="btn btn-s" onClick={() => clear(f)}>Clear</button>
                          <button className="btn btn-s" onClick={() => suspend(f.userId, f.name, f.suspended)}>
                            {f.suspended ? 'Restore' : 'Suspend'}
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      )}

      {tab === 1 && (
        <Card
          title="Playback log"
          note="The last 200 plays. Every play is granted at the moment of pressing play."
          actions={
            <input className="form-control" style={{ width: 240 }} placeholder="Search name, video, IP"
              value={q} onChange={(e) => setQ(e.target.value)} />
          }
        >
          {shownGrants.length === 0 ? <Empty title="No plays match that" /> : (
            <div className="table-responsive">
              <table className="table table-pib mb-0">
                <thead><tr><th>When</th><th>Who</th><th>Video</th><th>Network</th><th>Result</th></tr></thead>
                <tbody>
                  {shownGrants.map((g) => (
                    <tr key={g.id}>
                      <td className="mono">{fmtDateTime(g.issuedAt)}</td>
                      <td>
                        {g.name || 'Unknown'}
                        <div className="mono small muted">{g.email}</div>
                      </td>
                      <td>{g.video || <span className="text-muted">Removed video</span>}</td>
                      <td className="mono small">{g.ip || '—'}</td>
                      <td>
                        {g.outcome === 'REFUSED'
                          ? <span><Tag kind="stop">Refused</Tag> <span className="small">{g.reason}</span></span>
                          : <Tag kind="ok">Played</Tag>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      )}

      {tab === 2 && (
        <Card
          title="Video library"
          note="Every video the LMS plays. Ids are masked here; nobody needs one to run the course."
        >
          {videos.length === 0 ? <Empty title="No videos yet" /> : (
            <div className="table-responsive">
              <table className="table table-pib mb-0">
                <thead><tr><th>Title</th><th>Kind</th><th>Where it lives</th><th>Id</th><th>Plays, 30 days</th><th /></tr></thead>
                <tbody>
                  {videos.map((v) => (
                    <tr key={v.id}>
                      <td>
                        {v.title || <span className="text-muted">Untitled</span>}
                        {!v.active && <> <Tag kind="wait">Off</Tag></>}
                      </td>
                      <td className="small">{String(v.kind || '').toLowerCase()}</td>
                      <td className="small">{v.provider === 'CLOUDFLARE_STREAM' ? 'Cloudflare Stream'
                        : v.provider === 'UPLOAD' ? 'Uploaded here' : 'YouTube'}</td>
                      <td className="mono small">{v.masked}</td>
                      <td className="mono">{v.grantsLast30}</td>
                      <td className="text-end">
                        {v.provider !== 'UPLOAD' && (
                          <button className="btn btn-s" onClick={() => rotate(v)}>Rotate</button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      )}
    </Page>
  )
}
