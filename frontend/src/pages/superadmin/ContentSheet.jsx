import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, downloadFile, downloadPath } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { useDialog } from '../../components/Dialog'
import Drawer from '../../components/Drawer'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, Grid, LoadError, Page, Stat, Switch, Tabs, Tag, fmtDateTime } from '../../components/Ui'

/**
 * The master content sheet: where it lives, what the last sync did, and every earlier
 * copy of it.
 *
 * The sheet is the source and the catalogue follows it, one changed cell at a time.
 * Nothing here deletes: a row taken out of the sheet is reported, and its topic stays
 * until somebody removes it in Modules and chapters on purpose.
 */
const STATUS_TAG = { OK: 'ok', PARTIAL: 'wait', FAILED: 'stop', RUNNING: 'wait' }
const STATUS_LABEL = { OK: 'Done', PARTIAL: 'Done with problems', FAILED: 'Failed', RUNNING: 'Running' }
const TRIGGER_LABEL = {
  UPLOAD: 'Upload', MANUAL: 'Sync now', AUTO: 'Automatic', RETRY: 'Retry', RESTORE: 'Restore'
}
const SOURCE_LABEL = { UPLOAD: 'Uploaded', LINK: 'From the sheet link', RESTORE: 'Restored' }
const ACTION_TAG = { ADDED: 'ok', UPDATED: 'mono', REMOVED: 'wait', FAILED: 'stop', INVALID: 'stop' }
const ACTION_LABEL = {
  ADDED: 'New', UPDATED: 'Changed', REMOVED: 'Not in sheet', FAILED: 'Failed', INVALID: 'Invalid row'
}

export default function ContentSheet() {
  const toast = useToast()
  const { ask } = useDialog()
  const [status, setStatus] = useState(null)
  const [runs, setRuns] = useState([])
  const [versions, setVersions] = useState([])
  const [loadError, setLoadError] = useState(null)
  const [form, setForm] = useState({ sheetUrl: '', autoSync: false, intervalMinutes: 10 })
  const [saving, setSaving] = useState(false)
  const [busy, setBusy] = useState(false)
  const [file, setFile] = useState(null)
  const [openRun, setOpenRun] = useState(null)
  const [filter, setFilter] = useState(0)
  const fileInput = useRef(null)
  const watching = useRef(null)

  const load = async () => {
    try {
      const [s, r, v] = await Promise.all([
        api.get('/super/catalogue/content-sheet'),
        api.get('/super/catalogue/content-sheet/runs'),
        api.get('/super/catalogue/content-sheet/versions')
      ])
      setStatus(s)
      setRuns(r)
      setVersions(v)
      setLoadError(null)
      return s
    } catch (e) {
      setLoadError(e.message)
      return null
    }
  }

  useEffect(() => {
    load().then((s) => {
      if (s) {
        setForm({
          sheetUrl: s.config.sheetUrl || '',
          autoSync: !!s.config.autoSync,
          intervalMinutes: s.config.intervalMinutes || 10
        })
      }
    })
    return () => clearInterval(watching.current)
  }, [])

  /* while a sync runs, ask every two seconds; when it ends, open what it did */
  const follow = (runId) => {
    clearInterval(watching.current)
    watching.current = setInterval(async () => {
      const s = await load()
      if (s && !s.running) {
        clearInterval(watching.current)
        if (runId) showRun(runId)
      }
    }, 2000)
  }

  const showRun = async (id) => {
    try {
      const r = await api.get(`/super/catalogue/content-sheet/runs/${id}`)
      setFilter(0)
      setOpenRun(r)
    } catch (e) { toast.push(e.message, 'bad') }
  }

  if (loadError && !status) {
    return <Page title="Content sheet"><LoadError error={loadError} onRetry={load} /></Page>
  }
  if (!status) return <TableSkeleton />

  const started = (r, what) => {
    toast.push(`${what} started.`)
    setStatus((s) => ({ ...s, running: true }))
    follow(r.runId)
  }

  const saveConfig = async () => {
    setSaving(true)
    try {
      const r = await api.put('/super/catalogue/content-sheet/config', {
        sheetUrl: form.sheetUrl.trim(),
        autoSync: form.autoSync,
        intervalMinutes: Number(form.intervalMinutes) || 10
      })
      setStatus(r.status)
      setForm((f) => ({ ...f, autoSync: r.status.config.autoSync }))
      toast.push(r.check
        ? `Saved. The sheet was read: ${r.check.rows} rows on ${r.check.tabs.length} tab${r.check.tabs.length === 1 ? '' : 's'}.`
        : 'Saved.')
    } catch (e) { toast.push(e.message, 'bad') } finally { setSaving(false) }
  }

  const syncNow = async (preview) => {
    setBusy(true)
    try {
      started(await api.post('/super/catalogue/content-sheet/sync', { preview }), preview ? 'Preview' : 'Sync')
    } catch (e) { toast.push(e.message, 'bad') } finally { setBusy(false) }
  }

  const retry = async () => {
    setBusy(true)
    try {
      started(await api.post('/super/catalogue/content-sheet/retry'), 'Retry')
    } catch (e) { toast.push(e.message, 'bad') } finally { setBusy(false) }
  }

  const upload = async (preview) => {
    if (!file) return
    setBusy(true)
    try {
      const fd = new FormData()
      fd.append('file', file)
      const r = await api.upload(`/super/catalogue/content-sheet/upload?preview=${preview}`, fd)
      setFile(null)
      if (fileInput.current) fileInput.current.value = ''
      started(r, preview ? 'Preview' : 'Sync')
    } catch (e) { toast.push(e.message, 'bad') } finally { setBusy(false) }
  }

  const restore = async (v) => {
    const ok = await ask({
      title: 'Restore this version?',
      body: `The copy from ${fmtDateTime(v.createdAt)} becomes the current sheet and is synced. Topics that `
        + 'later versions added stay in the LMS; nothing is deleted. Automatic sync is switched off, '
        + 'so the linked sheet does not overwrite the restore. Change the linked sheet to match, then turn it back on.',
      confirmLabel: 'Restore and sync'
    })
    if (!ok) return
    setBusy(true)
    try {
      const r = await api.post(`/super/catalogue/content-sheet/versions/${v.id}/restore`)
      setForm((f) => ({ ...f, autoSync: false }))
      started(r, 'Restore')
    } catch (e) { toast.push(e.message, 'bad') } finally { setBusy(false) }
  }

  const template = () => downloadPath('/super/catalogue/content-sheet/template', 'content-sheet-template.xlsx')
    .catch((e) => toast.push(e.message, 'bad'))

  const cfg = status.config
  const last = status.lastRun
  const counts = last?.counts || {}
  const running = status.running
  const problems = (counts.failed || 0) + (counts.invalid || 0)

  return (
    <Page
      title="Content sheet"
      lede="One spreadsheet holds every module, chapter and topic with its video and materials. The LMS adds what is new and changes what was edited; it never deletes."
      actions={(
        <>
          <button className="btn" onClick={template}>Download template</button>
          <button className="btn" onClick={() => syncNow(true)} disabled={running || busy || !status.latestVersion && !cfg.sheetUrl}>
            Preview changes
          </button>
          <button className="btn btn-pib" onClick={() => syncNow(false)} disabled={running || busy || !status.latestVersion && !cfg.sheetUrl}>
            {running ? 'Syncing…' : 'Sync now'}
          </button>
        </>
      )}
    >
      <Card
        title="Last sync"
        note={running ? 'A sync is running. This page updates when it finishes.' : last
          ? `${TRIGGER_LABEL[last.trigger] || last.trigger}${last.preview ? ' (preview)' : ''} by ${last.actor}, ${fmtDateTime(last.startedAt)}`
          : 'Nothing has been synced yet.'}
        actions={last && !running && (
          <div className="d-flex gap-2">
            {problems > 0 && !last.preview && (
              <button className="btn btn-sm" onClick={retry} disabled={busy}>Retry failed</button>
            )}
            <button className="btn btn-sm" onClick={() => showRun(last.id)}>See details</button>
          </div>
        )}
      >
        {last ? (
          <>
            <Grid cols={6}>
              <Stat value={counts.added ?? 0} label="New" tone={counts.added ? 'var(--ok)' : undefined} />
              <Stat value={counts.updated ?? 0} label="Changed" />
              <Stat value={counts.unchanged ?? 0} label="Unchanged" />
              <Stat value={counts.removed ?? 0} label="Not in sheet" tone={counts.removed ? 'var(--warn)' : undefined} />
              <Stat value={problems} label="With problems" tone={problems ? 'var(--stop)' : undefined} />
              <Stat value={status.trackedRows} label="Rows tracked" />
            </Grid>
            <p className="small mt-3 mb-0">
              <Tag kind={STATUS_TAG[last.status] || 'mono'}>{STATUS_LABEL[last.status] || last.status}</Tag>
              {' '}{last.message}
            </p>
          </>
        ) : (
          <Empty title="No sync yet" icon="import">
            Set the Google Sheet link below, or upload the sheet, then press Sync now.
          </Empty>
        )}
      </Card>

      <Grid cols={2}>
        <Card title="Google Sheet link" note="Share the sheet as &ldquo;Anyone with the link can view&rdquo;.">
          <div className="d-grid gap-3">
            <div>
              <label className="form-label" htmlFor="cs-url">Sheet link</label>
              <input id="cs-url" className="form-control" value={form.sheetUrl}
                placeholder="https://docs.google.com/spreadsheets/d/..."
                onChange={(e) => setForm({ ...form, sheetUrl: e.target.value })} />
            </div>
            <div className="d-flex gap-3 align-items-center flex-wrap">
              <Switch checked={form.autoSync} disabled={!form.sheetUrl.trim()}
                onChange={(v) => setForm({ ...form, autoSync: v })}
                label="Sync automatically when the sheet changes" />
              <label className="small d-flex gap-2 align-items-center">
                Check every
                <input type="number" min="5" max="1440" className="form-control form-control-sm"
                  style={{ width: 80 }} value={form.intervalMinutes} aria-label="Minutes between checks"
                  onChange={(e) => setForm({ ...form, intervalMinutes: e.target.value })} />
                minutes
              </label>
            </div>
            <div className="d-flex gap-2 align-items-center">
              <button className="btn btn-pib" onClick={saveConfig} disabled={saving}>
                {saving ? 'Checking the sheet…' : 'Save'}
              </button>
              <span className="tiny muted">Saving reads the sheet once to check the link works.</span>
            </div>
            {cfg.lastCheckedAt && (
              <p className="tiny muted mb-0">
                Last checked {fmtDateTime(cfg.lastCheckedAt)}.
                {cfg.lastCheckError && <span className="text-danger"> {cfg.lastCheckError}</span>}
              </p>
            )}
          </div>
        </Card>

        <Card title="Upload the sheet" note="An .xlsx with the template's columns. Each upload is kept as a version.">
          <div className="d-grid gap-3">
            <input ref={fileInput} type="file" accept=".xlsx" className="form-control" aria-label="Content sheet file"
              onChange={(e) => setFile(e.target.files?.[0] || null)} />
            <div className="d-flex gap-2">
              <button className="btn" onClick={() => upload(true)} disabled={!file || busy || running}>Check first</button>
              <button className="btn btn-pib" onClick={() => upload(false)} disabled={!file || busy || running}>Upload and sync</button>
            </div>
            <div className="tiny muted">
              Columns: {(status.headers || []).join(' · ')}. Rows are matched on module, chapter and title.
              Several links in one cell each become a separate file.
            </div>
          </div>
        </Card>
      </Grid>

      <Card title="Sync history">
        {runs.length === 0 ? <Empty title="No syncs yet" /> : (
          <table className="table table-pib mb-0">
            <thead><tr><th>Started</th><th>How</th><th>By</th><th>Result</th><th>Summary</th><th /></tr></thead>
            <tbody>
              {runs.map((r) => (
                <tr key={r.id}>
                  <td className="mono">{fmtDateTime(r.startedAt)}</td>
                  <td>{TRIGGER_LABEL[r.trigger] || r.trigger}{r.preview ? ' (preview)' : ''}</td>
                  <td className="small">{r.actor}</td>
                  <td><Tag kind={STATUS_TAG[r.status] || 'mono'}>{STATUS_LABEL[r.status] || r.status}</Tag></td>
                  <td className="small">{r.message}</td>
                  <td className="text-end">
                    {r.status !== 'RUNNING' && (
                      <button className="btn btn-quiet btn-sm" onClick={() => showRun(r.id)}>Details</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Card title="Versions" note="A copy is kept every time the sheet's content changes.">
        {versions.length === 0 ? <Empty title="No versions yet" /> : (
          <table className="table table-pib mb-0">
            <thead><tr><th>Saved</th><th>Source</th><th>By</th><th>Rows</th><th /></tr></thead>
            <tbody>
              {versions.map((v, i) => (
                <tr key={v.id}>
                  <td className="mono">
                    {fmtDateTime(v.createdAt)}
                    {i === 0 && <> <Tag kind="ok">Current</Tag></>}
                  </td>
                  <td>{SOURCE_LABEL[v.source] || v.source}</td>
                  <td className="small">{v.createdBy}</td>
                  <td className="mono">{v.rowCount}</td>
                  <td className="text-end text-nowrap">
                    <button className="btn btn-quiet btn-sm"
                      onClick={() => downloadFile(v.fileId, v.filename).catch((e) => toast.push(e.message, 'bad'))}>
                      Download
                    </button>
                    {i > 0 && (
                      <button className="btn btn-quiet btn-sm" onClick={() => restore(v)} disabled={busy || running}>
                        Restore
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      {openRun && (
        <RunDrawer run={openRun} filter={filter} setFilter={setFilter} onClose={() => setOpenRun(null)} />
      )}
    </Page>
  )
}

/* the drawer's tabs, by index, as Tabs reports them */
const FILTERS = ['all', 'ADDED', 'UPDATED', 'REMOVED', 'problems']

function RunDrawer({ run, filter, setFilter, onClose }) {
  const items = run.items || []
  const which = FILTERS[filter] || 'all'
  const shown = items.filter((it) => {
    if (which === 'problems') return it.problems?.length > 0
    return which === 'all' || it.action === which
  })
  const n = (f) => items.filter((it) => (f === 'problems' ? it.problems?.length > 0 : it.action === f)).length

  return (
    <Drawer
      open
      wide
      title={`${TRIGGER_LABEL[run.trigger] || run.trigger}${run.preview ? ' (preview)' : ''}, ${fmtDateTime(run.startedAt)}`}
      subtitle={run.message}
      onClose={onClose}
    >
      <Tabs
        value={filter}
        onChange={setFilter}
        tabs={[
          `Everything (${items.length})`,
          `New (${n('ADDED')})`,
          `Changed (${n('UPDATED')})`,
          `Not in sheet (${n('REMOVED')})`,
          `Problems (${n('problems')})`
        ]}
      />
      {run.itemsTruncated && (
        <p className="tiny muted">Only the first {items.length} rows are listed; the counts cover all of them.</p>
      )}
      {(run.counts?.unchanged || 0) > 0 && (
        <p className="tiny muted mb-2">{run.counts.unchanged} unchanged rows are counted but not listed.</p>
      )}
      {shown.length === 0 ? <Empty title="Nothing here" /> : (
        <div className="d-grid gap-2 mt-2">
          {shown.map((it, i) => (
            <div key={i} className="border rounded p-2">
              <div className="d-flex gap-2 align-items-center flex-wrap">
                <Tag kind={ACTION_TAG[it.action] || 'mono'}>{ACTION_LABEL[it.action] || it.action}</Tag>
                <strong>{it.title || '(no title)'}</strong>
                <span className="tiny muted">
                  {[it.module, it.chapter].filter(Boolean).join(' › ')}
                  {it.row && ` · ${it.row}`}{it.sno && ` · S.no ${it.sno}`}
                </span>
                {it.chapterId && !run.preview && (
                  <Link className="tiny ms-auto" to={`/super/chapters/${it.chapterId}`}>Open chapter</Link>
                )}
              </div>
              {it.changes?.length > 0 && (
                <ul className="small mb-0 mt-1">
                  {it.changes.map((c, j) => <li key={j}>{c}</li>)}
                </ul>
              )}
              {it.problems?.length > 0 && (
                <ul className="small mb-0 mt-1 text-danger">
                  {it.problems.map((p, j) => <li key={j}>{p}</li>)}
                </ul>
              )}
            </div>
          ))}
        </div>
      )}
    </Drawer>
  )
}
