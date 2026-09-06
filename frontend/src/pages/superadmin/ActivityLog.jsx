import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api/client'
import { TableSkeleton } from '../../components/Skeletons'
import Avatar from '../../components/Avatar'
import { Card, Empty, Page } from '../../components/Ui'

const WINDOWS = [['7', 'Last 7 days'], ['30', 'Last 30 days'], ['0', 'Everything']]

/** Who changed what, across the whole console. */
export default function ActivityLog() {
  const [rows, setRows] = useState(null)
  const [who, setWho] = useState('')
  const [area, setArea] = useState('')
  const [days, setDays] = useState('7')

  useEffect(() => { api.get('/super/activity').then(setRows) }, [])

  const people = useMemo(
    () => [...new Set((rows || []).map((a) => a.actorEmail).filter(Boolean))], [rows])
  const areas = useMemo(
    () => [...new Set((rows || []).map((a) => a.entity).filter(Boolean))], [rows])

  if (!rows) return <TableSkeleton />

  const cutoff = days === '0' ? 0 : Date.now() - Number(days) * 86400000
  const shown = rows.filter((a) => (!who || a.actorEmail === who)
    && (!area || a.entity === area)
    && (!cutoff || new Date(a.createdAt).getTime() >= cutoff))

  return (
    <Page
      title="Activity log"
      lede="Provisioning, mentor assignment, catalogue changes, toggles and deletions."
    >
      <Card>
        <div className="row" style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginBottom: 16 }}>
          <div className="field" style={{ flex: '1 1 180px' }}>
            <label className="form-label">Person</label>
            <select className="form-select" value={who} onChange={(e) => setWho(e.target.value)}>
              <option value="">Everyone</option>
              {people.map((p) => <option key={p} value={p}>{p}</option>)}
            </select>
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
      </Card>
    </Page>
  )
}
