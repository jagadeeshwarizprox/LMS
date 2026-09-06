import { Fragment, useEffect, useState } from 'react'
import { api } from '../../api/client'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, Page, Tag, fmtDateTime } from '../../components/Ui'

export default function MailLog() {
  const [rows, setRows] = useState(null)
  const [open, setOpen] = useState(null)

  useEffect(() => { api.get('/admin/mail-log').then(setRows) }, [])
  if (!rows) return <TableSkeleton />

  return (
    <Page title="Credential and notification mail"
      lede="Every credential and notification mail the system has sent.">
      <Card note="Every mail is recorded here whether or not SMTP delivery is switched on.">
        {rows.length === 0 ? <Empty title="Nothing sent yet" /> : (
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
      </Card>
    </Page>
  )
}
