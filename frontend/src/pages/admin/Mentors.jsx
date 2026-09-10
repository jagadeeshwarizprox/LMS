import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api/client'
import { phoneError } from '../../api/validators'
import { useToast } from '../../context/ToastContext'
import { useDialog } from '../../components/Dialog'
import Avatar from '../../components/Avatar'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, Page, Stat, Tag } from '../../components/Ui'

/**
 * Mentors, owned by the admin.
 *
 * This was super admin only, which made the one person who configures the product also
 * the only one who could replace a mentor who left on a Tuesday. Admin runs the machine,
 * so it belongs here. What stays upstairs is the part that is not operational: pay,
 * granting the admin or super admin role, and deletion.
 *
 * Switching a mentor off is never a delete. Their name stays readable on every task
 * they reviewed and every handover on a learner's record, which is the whole reason
 * there is no delete button on this page.
 */
export default function Mentors() {
  const toast = useToast()
  const nav = useNavigate()
  const { ask } = useDialog()
  const [rows, setRows] = useState(null)
  const [error, setError] = useState(null)
  const [draft, setDraft] = useState({
    fullName: '', email: '', phone: '', whatsapp: '', reportsToId: ''
  })
  const [issued, setIssued] = useState(null)

  const load = () => api.get('/admin/people')
    .then((r) => { setRows(r); setError(null) })
    .catch((e) => setError(e.message))

  useEffect(() => { load() }, [])

  if (error) {
    return (
      <Page title="Mentors">
        <Empty title="That did not load" icon="risk" action={
          <button className="btn btn-pib" onClick={load}>Try again</button>
        }>{error}</Empty>
      </Page>
    )
  }
  if (!rows) return <TableSkeleton />

  const mentors = rows.filter((p) => p.role === 'MENTOR')
  const seniors = mentors.filter((m) => m.active)

  /* the boxes took anything, so a mentor could be saved with a name in the phone field
     and nobody found out until somebody tried to ring them */
  const phoneProblem = phoneError(draft.phone)
  const whatsappProblem = phoneError(draft.whatsapp)

  const add = async () => {
    try {
      const r = await api.post('/admin/people', draft)
      setDraft({ fullName: '', email: '', phone: '', whatsapp: '', reportsToId: '' })
      setIssued(r)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const setActive = async (m, active) => {
    if (!active && m.learners > 0) {
      const r = await ask({
        title: `Move ${m.name}'s learners first`,
        body: `${m.name} still holds ${m.learners} learner${m.learners === 1 ? '' : 's'}. `
            + 'Pick who takes them, and they move before the account is switched off.',
        fields: [{
          name: 'toId', label: 'Move them to',
          options: seniors.filter((x) => x.id !== m.id).map((x) => ({ value: x.id, label: x.name })),
          required: true
        }, {
          name: 'reason', label: 'Reason, kept on each record', required: true
        }],
        confirmLabel: 'Move and switch off'
      })
      if (!r) return
      try {
        await api.post(`/admin/people/${m.id}/reassign-learners`, { toId: r.toId, reason: r.reason })
      } catch (e) { toast.push(e.message, 'bad'); return }
    }
    try {
      await api.post(`/admin/people/${m.id}/active`, { active })
      toast.push(active ? `${m.name} is back on.` : `${m.name} switched off. Their history stays.`)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const edit = async (m) => {
    const r = await ask({
      title: `Edit ${m.name}`,
      fields: [
        { name: 'fullName', label: 'Name', value: m.name, required: true },
        { name: 'phone', label: 'Phone', value: m.phone || '', validate: phoneError },
        { name: 'whatsapp', label: 'WhatsApp', value: m.whatsapp || '', validate: phoneError },
        {
          name: 'reportsToId', label: 'Reports to', value: m.reportsToId || '',
          options: [{ value: '', label: 'Nobody' }].concat(
            rows.filter((x) => x.id !== m.id).map((x) => ({ value: x.id, label: x.name })))
        }
      ],
      confirmLabel: 'Save'
    })
    if (!r) return
    try {
      await api.post('/admin/people', { id: m.id, email: m.email, active: m.active, ...r })
      toast.push('Saved.')
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const resetPassword = async (m) => {
    const r = await ask({
      title: `Reset ${m.name}'s password`,
      body: 'Back to the password made from their name, and they are made to change it on the '
          + 'way in. The clock on it starts again, so a reissue is never weaker than the original.',
      confirmLabel: 'Reset'
    })
    if (!r) return
    try {
      const out = await api.post(`/admin/people/${m.id}/reset-password`, {})
      setIssued({ fullName: m.name, email: m.email, loginId: out.loginId, password: out.password })
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const set = (k) => (e) => setDraft((d) => ({ ...d, [k]: e.target.value }))

  return (
    <Page title="Mentors"
      lede="Who teaches, who they report to, and who holds how many learners.">
      <div className="row g-3 mb-4">
        <div className="col-6 col-lg-4"><Stat value={mentors.length} label="Mentors" /></div>
        <div className="col-6 col-lg-4">
          <Stat value={mentors.filter((m) => m.active).length} label="Active" />
        </div>
        <div className="col-12 col-lg-4">
          <Stat value={mentors.reduce((n, m) => n + (m.learners || 0), 0)} label="Learners held" />
        </div>
      </div>

      {issued && (
        <Card title="Credentials, shown once"
          note="Not stored anywhere readable. Pass these on before you dismiss this."
          actions={<button className="btn btn-quiet" onClick={() => setIssued(null)}>Dismiss</button>}
        >
          <div className="row g-3">
            <div className="col-md-4">
              <div className="eyebrow mb-1">Who</div>
              <strong>{issued.fullName}</strong>
            </div>
            <div className="col-md-4">
              <div className="eyebrow mb-1">Login ID or email</div>
              <div className="mono">{issued.loginId}</div>
              <div className="mono small muted">{issued.email}</div>
            </div>
            <div className="col-md-4">
              <div className="eyebrow mb-1">First password</div>
              <div className="mono">{issued.password}</div>
            </div>
          </div>
        </Card>
      )}

      <Card title="Add a mentor"
        note="They sign in with the login ID or the email, against a first password made from their name, and are made to change it.">
        <div className="row g-2 align-items-end">
          <div className="col-md-3">
            <label className="form-label">Name</label>
            <input className="form-control" value={draft.fullName} onChange={set('fullName')} />
          </div>
          <div className="col-md-3">
            <label className="form-label">Email</label>
            <input className="form-control" value={draft.email} onChange={set('email')} />
          </div>
          <div className="col-md-2">
            <label className="form-label">Phone</label>
            <input
              className={`form-control ${phoneProblem ? 'is-invalid' : ''}`}
              inputMode="tel" value={draft.phone} onChange={set('phone')}
            />
            {phoneProblem && <div className="invalid-feedback">{phoneProblem}</div>}
          </div>
          <div className="col-md-2">
            <label className="form-label">WhatsApp</label>
            <input
              className={`form-control ${whatsappProblem ? 'is-invalid' : ''}`}
              inputMode="tel" placeholder="Same as phone if blank"
              value={draft.whatsapp} onChange={set('whatsapp')}
            />
            {whatsappProblem && <div className="invalid-feedback">{whatsappProblem}</div>}
          </div>
          <div className="col-md-2">
            <label className="form-label">Reports to</label>
            <select className="form-select" value={draft.reportsToId} onChange={set('reportsToId')}>
              <option value="">Nobody</option>
              {rows.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
          </div>
          <div className="col-md-1">
            <button className="btn btn-pib w-100" onClick={add}
              disabled={!draft.fullName || !draft.email || !!phoneProblem || !!whatsappProblem}
            >Add</button>
          </div>
        </div>
      </Card>

      <Card title="Everyone teaching">
        {mentors.length === 0 ? <Empty title="No mentors yet" /> : (
          <div className="table-responsive">
            <table className="table table-pib mb-0">
              <thead>
                <tr><th>Mentor</th><th>Reports to</th><th>Learners</th><th>State</th><th /></tr>
              </thead>
              <tbody>
                {mentors.map((m) => (
                  <tr key={m.id}>
                    <td>
                      <span className="who-cell">
                        <Avatar name={m.name} size={32} />
                        <span>
                          {m.name}
                          <div className="mono small" style={{ color: 'var(--ink-30)' }}>{m.email}</div>
                        </span>
                      </span>
                    </td>
                    <td>{m.reportsTo || '\u2014'}</td>
                    <td className="mono">{m.learners ?? 0}</td>
                    <td>{m.active ? <Tag kind="ok">Active</Tag> : <Tag kind="batch">Off</Tag>}</td>
                    <td className="text-end d-flex gap-2 justify-content-end">
                      <button
                        className="btn btn-quiet btn-sm"
                        onClick={() => nav(`/admin/register?mentorId=${m.id}`)}
                        disabled={!m.learners}
                        title={m.learners
                          ? `Open the register filtered to ${m.name}`
                          : 'Nobody is assigned to them yet'}
                      >
                        View learners
                      </button>
                      <button className="btn btn-quiet btn-sm" onClick={() => edit(m)}>Edit</button>
                      <button className="btn btn-quiet btn-sm" onClick={() => resetPassword(m)}>
                        Reset password
                      </button>
                      <button className="btn btn-quiet btn-sm" onClick={() => setActive(m, !m.active)}>
                        {m.active ? 'Switch off' : 'Switch on'}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </Page>
  )
}
