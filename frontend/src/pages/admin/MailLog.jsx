import { Fragment, useState } from 'react'
import { api } from '../../api/client'
import Pager, { useDebounced, usePagedList } from '../../components/Pager'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, LoadError, Page, Tag, fmtDateTime } from '../../components/Ui'

/* the kinds the backend writes; a kind not listed here still shows under "Every kind" */
const KINDS = ['CREDENTIALS', 'STAFF_CREDENTIALS', 'PASSWORD_RESET', 'PASSWORD_CHANGED',
  'REVIEW', 'MESSAGE', 'PROJECT', 'ANNOUNCEMENT', 'CALL', 'GENERIC']
const STATUSES = ['SENT', 'LOGGED', 'FAILED']

/*
 * The mail log, a page of fifty at a time, newest first. It used to show the last
 * hundred mails, which on an enrolment day is the last hour; the recipient box is how
 * somebody asking "did my credentials go out yesterday" gets an answer.
 */
export default function MailLog() {
  const [open, setOpen] = useState(null)
  const [to, setTo] = useState('')
  const [kind, setKind] = useState('')
  const [status, setStatus] = useState('')
  const toSettled = useDebounced(to.trim(), 300)
  const list = usePagedList((qs) => api.get(`/admin/mail-log?${qs}`), { to: toSettled, kind, status })
  const rows = list.data ? list.rows : null

  if (list.error && !list.data) {
    return <Page title="Credential and notification mail"><LoadError error={list.error} onRetry={list.reload} /></Page>
  }
  if (!rows) return <TableSkeleton />

  return (
    <Page title="Credential and notification mail"
      lede="Every credential and notification mail the system has sent.">
      <Card
        note="Every mail is recorded here whether or not SMTP delivery is switched on."
        actions={
          <div className="d-flex gap-2 flex-wrap">
            <input className="input-quiet" value={to} placeholder="Recipient starts with"
              aria-label="Recipient" onChange={(e) => setTo(e.target.value)} />
            <select value={kind} onChange={(e) => setKind(e.target.value)} aria-label="Kind">
              <option value="">Every kind</option>
              {KINDS.map((k) => <option key={k} value={k}>{k}</option>)}
            </select>
            <select value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Status">
              <option value="">Any status</option>
              {STATUSES.map((k) => <option key={k} value={k}>{k}</option>)}
            </select>
          </div>
        }
      >
        {rows.length === 0 ? <Empty title={to || kind || status ? 'No mail matches' : 'Nothing sent yet'} /> : (
          <table className="table table-pib mb-0">
            <thead><tr><th>Sent</th><th>To</th><th>Subject</th><th>Kind</th><th>Status</th><th /></tr></thead>
            <tbody>
              {rows.map((m) => (
                <Fragment key={m.id}>
                  <tr>
                    <td className="mono">{fmtDateTime(m.sentAt)}</td>
                    <td className="mono">{m.toEmail}</td>
                    <td>{m.subject}</td>
                    <td>{m.kind}</td>
                    <td>
                      <Tag kind={m.status === 'SENT' ? 'ok' : m.status === 'FAILED' ? 'stop' : 'wait'}>
                        {m.status}
                      </Tag>
                    </td>
                    <td className="text-end">
                      <button className="btn btn-quiet" onClick={() => setOpen(open === m.id ? null : m.id)}>
                        {open === m.id ? 'Hide' : 'View'}
                      </button>
                    </td>
                  </tr>
                  {open === m.id && (
                    <tr>
                      <td colSpan={6}>
                        <pre className="mono mb-0" style={{
                          whiteSpace: 'pre-wrap', fontSize: '.8rem',
                          background: 'var(--blue-050)', padding: 12, borderRadius: 8
                        }}>{m.body}</pre>
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>
        )}
        <Pager page={list.page} size={list.size} total={list.total}
          onPage={list.setPage} loading={list.loading} noun="mails" />
      </Card>
    </Page>
  )
}
