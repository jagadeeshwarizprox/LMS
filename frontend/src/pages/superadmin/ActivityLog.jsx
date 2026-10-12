import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { TableSkeleton } from '../../components/Skeletons'
import Avatar from '../../components/Avatar'
import Pager, { useDebounced, usePagedList } from '../../components/Pager'
import { Card, Empty, LoadError, Page } from '../../components/Ui'

const WINDOWS = [['7', 'Last 7 days'], ['30', 'Last 30 days'], ['0', 'Everything']]

/* yyyy-MM-dd for the day `days` ago, in local time, which is what the server reads */
function dayAgo(days) {
  const d = new Date(Date.now() - days * 86400000)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/**
 * Who changed what, across the whole console.
 *
 * Filtered and paged on the server. This used to load the latest hundred entries and
 * filter those, so "everything" meant the last afternoon and a person who had not acted
 * recently was not even in the dropdown. The person box is now the start of their
 * email, typed, and the areas come from the whole log.
 */
export default function ActivityLog() {
  const [who, setWho] = useState('')
  const [area, setArea] = useState('')
  const [days, setDays] = useState('7')
  const [areas, setAreas] = useState([])

  useEffect(() => {
    api.get('/super/activity/areas').then((a) => setAreas((a || []).filter(Boolean).sort()))
      .catch(() => setAreas([]))
  }, [])

  const actor = useDebounced(who.trim(), 300)
  const list = usePagedList((qs) => api.get(`/super/activity?${qs}`), {
    actor, area, from: days === '0' ? '' : dayAgo(Number(days))
  })
  const rows = list.data ? list.rows : null
  const loadError = list.error
  const load = list.reload

  if (loadError && !rows) return <Page title="Activity log"><LoadError error={loadError} onRetry={load} /></Page>
  if (!rows) return <TableSkeleton />

  const shown = rows

  return (
    <Page
      title="Activity log"
      lede="Provisioning, mentor assignment, catalogue changes, toggles and deletions."
    >
      <Card>
        <div className="row" style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginBottom: 16 }}>
          <div className="field" style={{ flex: '1 1 180px' }}>
            <label className="form-label" htmlFor="act-who">Person</label>
            <input id="act-who" className="form-control" value={who}
              placeholder="Email starts with" onChange={(e) => setWho(e.target.value)} />
          </div>
          <div className="field" style={{ flex: '1 1 180px' }}>
            <label className="form-label">Area</label>
            <select className="form-select" value={area} onChange={(e) => setArea(e.target.value)}>
              <option value="">Everything</option>
              {areas.map((a) => <option key={a} value={a}>{a}</option>)}
            </select>
          </div>
          <div className="field" style={{ flex: '1 1 180px' }}>
            <label className="form-label">When</label>
            <select className="form-select" value={days} onChange={(e) => setDays(e.target.value)}>
              {WINDOWS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </select>
          </div>
        </div>

        {shown.length === 0 ? <Empty title="Nothing in this window" /> : shown.map((a) => {
          const name = (a.actorEmail || 'system').split('@')[0]
          return (
            <div className="act" key={a.id}>
              <div className="when">
                {new Date(a.createdAt).toLocaleString('en-IN', {
                  day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit'
                })}
              </div>
              <div>
                <Avatar name={name} size={22} />
                <span className="who" style={{ marginLeft: 6 }}>{name}</span>
                <span className="tag tag-batch" style={{ margin: '0 6px' }}>{a.entity}</span>
                {a.detail || a.action}
              </div>
            </div>
          )
        })}
        <Pager page={list.page} size={list.size} total={list.total}
          onPage={list.setPage} loading={list.loading} noun="entries" />
      </Card>
    </Page>
  )
}
