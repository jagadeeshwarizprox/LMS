import { useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { useDialog } from '../../components/Dialog'
import { TableSkeleton } from '../../components/Skeletons'
import Icon from '../../components/Icon'
import Avatar from '../../components/Avatar'
import { Card, Empty, Grid, Note, LoadError, Page, Pct, Stat, Tag } from '../../components/Ui'

/*
 * No SUPER_ADMIN.
 *
 * The role hands out every permission there is, including the one that would remove the
 * account that granted it, so it is not something the running product mints. The first
 * one comes from the deployment. The server refuses it too, because a dropdown is not a
 * permission.
 */
const ROLES = ['MENTOR', 'ADMIN']

export default function People() {
  const toast = useToast()
  const { ask } = useDialog()
  const [rows, setRows] = useState(null)
  const [draft, setDraft] = useState({ fullName: '', email: '', role: 'MENTOR', reportsToId: '', password: '' })
  const [created, setCreated] = useState(null)

  const [loadError, setLoadError] = useState(null)

  const load = () => api.get('/super/people')
    .then((r) => { setRows(r); setLoadError(null) })
    .catch((e) => setLoadError(e.message))
  useEffect(() => { load() }, [])
  if (loadError) {
    return <Page title="Admin and mentors"><LoadError error={loadError} onRetry={load} /></Page>
  }
  if (!rows) return <TableSkeleton />

  const save = async () => {
    try {
      const r = await api.post('/super/people', draft)
      /* the password is generated on the server and never stored in a readable
         form, so this is the only moment it can be passed on */
      if (r.password) setCreated(r)
      else toast.push('Saved.')
      setDraft({ fullName: '', email: '', role: 'MENTOR', reportsToId: '', password: '' })
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const changeManager = async (person, reportsToId) => {
    try {
      await api.post('/super/people', {
        id: person.id, fullName: person.name, email: person.email,
        role: person.role, reportsToId, active: person.active
      })
      toast.push('Reporting line updated.')
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const set = (k) => (e) => setDraft((d) => ({ ...d, [k]: e.target.value }))

  /* the same button admins have on the learner register, for the same reason */
  const edit = async (p) => {
    const r = await ask({
      title: `Edit ${p.name}`,
      fields: [
        { name: 'fullName', label: 'Name', required: true, value: p.name },
        { name: 'email', label: 'Email', required: true, value: p.email },
        {
          name: 'role', label: 'Role', value: p.role,
          options: ROLES.map((x) => ({ value: x, label: x.replace('_', ' ') }))
        }
      ],
      confirmLabel: 'Save'
    })
    if (!r) return
    try {
      await api.post('/super/people', {
        id: p.id, ...r, reportsToId: p.reportsToId, active: p.active
      })
      toast.push('Saved.')
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  /* before somebody leaves: twelve learners is twelve dialogs from the register, and
     one of them gets missed */
  const reassign = async (p) => {
    const others = rows.filter((x) => x.id !== p.id && x.active && x.role !== 'LEARNER')
    if (others.length === 0) { toast.push('Nobody else to move them to.', 'bad'); return }
    const r = await ask({
      title: `Move ${p.learners} learner${p.learners === 1 ? '' : 's'} off ${p.name}`,
      body: 'Every learner currently with them moves across. Mentor history keeps the '
        + 'change, so their old work still reads correctly.',
      fields: [
        {
          name: 'toId', label: 'Move them to', required: true,
          options: others.map((x) => ({ value: x.id, label: `${x.name} · ${x.learners} learners` }))
        },
        { name: 'reason', label: 'Reason', multiline: true, placeholder: 'Leaving, on cover, rebalancing' }
      ],
      confirmLabel: 'Move them'
    })
    if (!r) return
    try {
      const out = await api.post(`/super/people/${p.id}/reassign-learners`, r)
      toast.push(`${out.moved} moved to ${out.to}.`)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const toggleActive = async (p) => {
    if (p.active) {
      const ok = await ask({
        title: `Switch off ${p.name}?`,
        body: 'They cannot sign in and cannot be picked as a mentor. Nothing is deleted, '
          + 'so their name still reads correctly on everything they did.',
        confirmLabel: 'Switch off',
        intent: 'danger'
      })
      if (!ok) return
    }
    try {
      await api.post(`/super/people/${p.id}/active`, { active: !p.active })
      toast.push(p.active ? 'Switched off.' : 'Switched back on.')
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const resetPassword = async (p) => {
    const ok = await ask({
      title: `Reset the password for ${p.name}?`,
      body: 'It goes back to the one made from their name, they are forced to change it '
        + 'at the next sign in, and whatever they had set stops working.',
      confirmLabel: 'Reset it'
    })
    if (!ok) return
    try {
      const r = await api.post(`/super/people/${p.id}/reset-password`, {})
      setCreated({ fullName: p.name, ...r })
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const admins = rows.filter((p) => p.role === 'ADMIN')
  const mentors = rows.filter((p) => p.role === 'MENTOR')
  const activeMentors = mentors.filter((p) => p.active)
  const covered = rows.reduce((a, p) => a + (p.learners || 0), 0)
  const loadOf = (p) => Math.min(100, Math.round(((p.learners || 0) / 16) * 100))

  const personCell = (p) => (
    <span className="who-cell">
      <Avatar name={p.name} size={32} />
      <span>
        <span className="who-name">{p.name}</span>
        <span className="who-sub mono">{p.loginId ? `${p.loginId} \u00b7 ${p.email}` : p.email}</span>
      </span>
    </span>
  )

  const actions = (p) => (
    <div className="row-actions">
      {p.learners > 0 && (
        <button className="btn btn-s" onClick={() => reassign(p)}>Move learners</button>
      )}
      <button className="btn btn-s" onClick={() => edit(p)}>Edit</button>
      <button className="btn btn-s" onClick={() => resetPassword(p)}>Reset</button>
      <button className="btn btn-s btn-x" onClick={() => toggleActive(p)}>
        {p.active ? 'Switch off' : 'Switch on'}
      </button>
    </div>
  )

  return (
    <Page
      title="Admin and mentors"
      lede="You create every account here. One admin runs the day, mentors teach."
    >
      {created && (
        <Card>
          <div className="new-account">
            <Icon name="check" size={20} />
            <div className="new-account-body">
              <strong>{created.fullName} can sign in now</strong>
              <p className="small muted mb-2">
                Their credentials have been mailed. This password is shown once and is not
                stored anywhere readable, so copy it now if mail is switched off.
              </p>
              <div className="new-account-creds">
                <span>Login ID <code>{created.loginId || created.email}</code></span>
                <span>Password <code>{created.password}</code></span>
              </div>
              <p className="small muted mt-2 mb-0">
                They sign in on the team page with that login ID or with their email, and
                are asked to set their own password before anything else opens.
              </p>
            </div>
            <button className="btn btn-quiet" onClick={() => setCreated(null)}>Done</button>
          </div>
        </Card>
      )}

      <Note>
        <b>One admin only.</b> The admin account is created and reset from this page.
        Adding students stays with the admin; mentors never add students.
      </Note>

      <Card title="Add someone" note="Reporting is transitive, so a lead sees everything under their whole tree.">
        <div className="row g-2 align-items-end">
          <div className="col-md-3">
            <label className="form-label">Name</label>
            <input className="form-control" value={draft.fullName} onChange={set('fullName')} />
          </div>
          <div className="col-md-3">
            <label className="form-label">Email</label>
            <input className="form-control" type="email" value={draft.email} onChange={set('email')} />
          </div>
          <div className="col-md-2">
            <label className="form-label">Role</label>
            <select className="form-select" value={draft.role} onChange={set('role')}>
              {ROLES.map((r) => <option key={r} value={r}>{r.replace('_', ' ')}</option>)}
            </select>
          </div>
          <div className="col-md-2">
            <label className="form-label">Reports to</label>
            <select className="form-select" value={draft.reportsToId} onChange={set('reportsToId')}>
              <option value="">Nobody</option>
              {rows.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
          </div>
          <div className="col-md-2">
            <button className="btn btn-pib w-100" onClick={save} disabled={!draft.email || !draft.fullName}>
              Add
            </button>
          </div>
        </div>
      </Card>

      <Grid cols={3} style={{ marginBottom: 16 }}>
        <Stat value={admins.length} label="Admin" icon="security"
          hint={admins.length ? admins.map((a) => a.name).join(', ') : 'not created yet'} />
        <Stat value={activeMentors.length} label="Active mentors" icon="people"
          hint={`${mentors.length - activeMentors.length} switched off`} />
        <Stat value={covered} label="Learners with a mentor" icon="batch" />
        <Stat value={activeMentors.length ? Math.round(covered / activeMentors.length) : 0}
          label="Average load" icon="projects" hint="learners per active mentor" />
      </Grid>

      <Card
        title="The admin"
        note="Signs in with the user ID or the email. The password is set by you."
      >
        {admins.length === 0 ? (
          <Empty title="No admin yet">
            Nobody can enrol a learner until one exists. Add them above with the Admin role.
          </Empty>
        ) : (
          <table className="table table-pib mb-0">
            <thead><tr><th>Name</th><th>Reports to</th><th>Status</th><th /></tr></thead>
            <tbody>
              {admins.map((p) => (
                <tr key={p.id}>
                  <td>{personCell(p)}</td>
                  <td>
                    <select className="form-select form-select-sm" value={p.reportsToId || ''}
                      onChange={(e) => changeManager(p, e.target.value)}>
                      <option value="">Nobody</option>
                      {rows.filter((x) => x.id !== p.id).map((x) => (
                        <option key={x.id} value={x.id}>{x.name}</option>
                      ))}
                    </select>
                  </td>
                  <td>
                    {p.active ? <Tag kind="ok">Active</Tag> : <Tag kind="stop">Off</Tag>}
                    {p.active && p.neverSignedIn && <Tag kind="warn">Never signed in</Tag>}
                  </td>
                  <td className="text-end">{actions(p)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Card
        title="Mentors"
        note="Switching someone off keeps their history and stops them signing in."
      >
        {mentors.length === 0 ? <Empty title="No mentors yet" /> : (
          <div className="tbl-wrap">
            <table className="table table-pib mb-0">
              <thead>
                <tr>
                  <th>Name</th><th>Reports to</th><th>Learners</th>
                  <th>Load</th><th>Status</th><th />
                </tr>
              </thead>
              <tbody>
                {mentors.map((p) => (
                  <tr key={p.id} className={p.active ? '' : 'is-off'}>
                    <td>{personCell(p)}</td>
                    <td>
                      <select className="form-select form-select-sm" value={p.reportsToId || ''}
                        onChange={(e) => changeManager(p, e.target.value)}>
                        <option value="">Nobody</option>
                        {rows.filter((x) => x.id !== p.id).map((x) => (
                          <option key={x.id} value={x.id}>{x.name}</option>
                        ))}
                      </select>
                    </td>
                    <td className="mono">{p.learners || 0}</td>
                    <td style={{ width: 150 }}>
                      {p.active ? <Pct value={loadOf(p)} /> : <span className="tiny muted">&mdash;</span>}
                    </td>
                    <td>
                      {p.active ? <Tag kind="ok">Active</Tag> : <Tag kind="stop">Off</Tag>}
                      {p.active && p.neverSignedIn && <Tag kind="warn">Never signed in</Tag>}
                    </td>
                    <td className="text-end">{actions(p)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Card title="Reporting line" note="Everyone above a learner's mentor can open that learner.">
        <div className="chips">
          {['Super Admin', 'Admin', 'Mentor', 'Learner'].map((n, i, a) => (
            <span key={n}>
              <span className={`chip ${i < 3 ? 'on' : ''}`}>{n}</span>
              {i < a.length - 1 && <span className="chip plain">&rarr;</span>}
            </span>
          ))}
        </div>
      </Card>
    </Page>
  )
}
