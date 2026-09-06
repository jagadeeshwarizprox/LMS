import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../../api/client'
import { useToast } from '../../context/ToastContext'
import { useDialog } from '../../components/Dialog'
import Avatar from '../../components/Avatar'
import { TableSkeleton } from '../../components/Skeletons'
import { Card, Empty, Page, Tag, TrackTag, fmtDateTime } from '../../components/Ui'

export default function Register() {
  const toast = useToast()
  const { ask } = useDialog()
  const [rows, setRows] = useState(null)
  const [mentors, setMentors] = useState([])
  const [track, setTrack] = useState('')
  const [q, setQ] = useState('')
  const [enrol, setEnrol] = useState({ fullName: '', email: '', phone: '', whatsapp: '', bundleId: '', trackType: 'PREMIUM', batchId: '', mentorId: '', joinedOn: new Date().toISOString().slice(0, 10) })
  const [showEnrol, setShowEnrol] = useState(false)
  const [courses, setCourses] = useState([])
  const [batches, setBatches] = useState([])
  /* what will be issued, fetched as they type the name, so nobody has to guess */
  const [preview, setPreview] = useState(null)
  /* what WAS issued, kept on screen until dismissed: this is the one moment the
     password is readable, and an admin is usually on the phone when it appears */
  const [issued, setIssued] = useState(null)
  const [failed, setFailed] = useState(null)
  const [lockouts, setLockouts] = useState([])

  const loadLockouts = () =>
    api.get('/admin/access/lockouts').then(setLockouts).catch(() => setLockouts([]))

  const unlock = async (row) => {
    try {
      await api.post('/admin/access/lockouts/unlock', { email: row.email })
      toast.push(`${row.email} can sign in again.`)
      await loadLockouts()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  /* a failed load used to leave rows null, which is the skeleton, which is a page that
     never finishes loading and never says why */
  const load = async () => {
    const params = new URLSearchParams()
    if (track) params.set('trackType', track)
    if (q) params.set('q', q)
    try {
      setRows(await api.get(`/admin/register?${params}`))
      setFailed(null)
    } catch (e) {
      setRows([])
      setFailed(e.message)
    }
  }

  useEffect(() => { load() }, [track, q])
  useEffect(() => {
    /* /super/people is super admin only, so an admin got a 403 here and an empty
       mentor dropdown on the page whose job is assigning mentors */
    api.get('/admin/mentors').then((p) => setMentors(p.filter((x) => x.active !== false)))
      .catch(() => setMentors([]))
    api.get('/super/catalogue').then((t) => setCourses(t.bundles || [])).catch(() => setCourses([]))
    api.get('/admin/batches').then(setBatches).catch(() => setBatches([]))
    loadLockouts()
  }, [])

  /* the name is the only input either credential depends on, so it is the only one
     worth asking the server about */
  useEffect(() => {
    if (!showEnrol || !enrol.fullName.trim()) { setPreview(null); return }
    const t = setTimeout(() => {
      const p = new URLSearchParams({ fullName: enrol.fullName, email: enrol.email || '' })
      api.get(`/admin/credential-preview?${p}`).then(setPreview).catch(() => setPreview(null))
    }, 350)
    return () => clearTimeout(t)
  }, [enrol.fullName, enrol.email, showEnrol])

  if (!rows) return <TableSkeleton />

  /**
   * Mentors are sticky on purpose: the relationship carries the learner's context.
   * A change is deliberate, needs a reason, and is kept on the record.
   */
  const changeMentor = async (l) => {
    const r = await ask({
      title: l.mentor ? `Move ${l.name} to another mentor` : `Assign a mentor to ${l.name}`,
      body: l.mentor
        ? `Currently with ${l.mentor}. Mentors stay with a learner on purpose, because they carry `
          + 'the context. A change is kept on the record with the reason.'
        : 'Leave the mentor empty to let round robin pick the next one.',
      fields: [
        {
          name: 'mentorId', label: 'New mentor', required: true,
          options: mentors.map((m) => ({ value: m.id, label: `${m.name} · ${m.learners} learners` }))
        },
        {
          name: 'reason', label: 'Reason', required: Boolean(l.mentor), multiline: true,
          placeholder: 'Why is this learner moving?',
          hint: 'Kept on the learner record for good.'
        }
      ],
      confirmLabel: l.mentor ? 'Move learner' : 'Assign'
    })
    if (!r) return
    try {
      await api.post(`/admin/learners/${l.learnerId}/mentor`,
        { mentorId: r.mentorId, reason: r.reason })
      toast.push('Mentor updated.')
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const resend = async (learnerId) => {
    try {
      const r = await api.post(`/admin/learners/${learnerId}/resend-credentials`, {})
      toast.push(`Login mailed to ${r.sentTo}.`)
    } catch (e) { toast.push(e.message, 'bad') }
  }

  /* the message an admin actually gets is "I cannot log in", so the answer is one
     button that puts the account back to the password made from their own name */
  const resetPassword = async (l) => {
    const ok = await ask({
      title: `Reset the password for ${l.name}?`,
      body: 'It goes back to the one made from their name, they are forced to change it '
        + 'at the next sign in, and whatever they had set stops working.',
      confirmLabel: 'Reset it'
    })
    if (!ok) return
    try {
      const r = await api.post(`/admin/learners/${l.learnerId}/reset-password`, {})
      setIssued({ name: l.name, ...r })
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const submitEnrol = async () => {
    try {
      const r = await api.post('/admin/enrol', enrol)
      if (r.password) setIssued({ name: enrol.fullName, ...r })
      else toast.push(`${r.status}: ${r.detail}`)
      setEnrol({ fullName: '', email: '', phone: '', whatsapp: '', bundleId: '', trackType: 'PREMIUM', batchId: '', mentorId: '', joinedOn: new Date().toISOString().slice(0, 10) })
      setShowEnrol(false)
      await load()
    } catch (e) { toast.push(e.message, 'bad') }
  }

  const set = (k) => (e) => setEnrol((d) => ({ ...d, [k]: e.target.value }))

  return (
    <Page
      title="All learners"
      lede="Every learner on both tracks, with their mentor, batch and course."
      actions={<button className="btn btn-pib" onClick={() => setShowEnrol((v) => !v)}>Enrol one learner</button>}
    >
      {/*
        * Locked out accounts, moved here from the video security page when that page was
        * removed. This is where the rest of the sign-in trouble already lives: issuing
        * credentials, resending them, resetting a password. Somebody locked out is
        * somebody who cannot sign in, so it belongs next to those and not on a page about
        * video. It only appears when there is somebody to let back in.
        */}
      {lockouts.length > 0 && (
        <Card
          title="Locked out"
          note="Shut out by the sign-in guard after repeated failed attempts"
        >
          <table className="table table-pib mb-0">
            <thead>
              <tr><th>Account</th><th>Failed attempts</th><th>Until</th><th /></tr>
            </thead>
            <tbody>
              {lockouts.map((row) => (
                <tr key={row.email}>
                  <td>{row.email}</td>
                  <td className="mono">{row.attempts ?? row.failures ?? '\u2014'}</td>
                  <td className="mono">{row.until ? fmtDateTime(row.until) : '\u2014'}</td>
                  <td className="text-end">
                    <button className="btn btn-quiet" onClick={() => unlock(row)}>Unlock</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      {failed && (
        <Card title="The learner list did not load">
          <p className="small muted mb-2">{failed}</p>
          <button className="btn btn-pib" onClick={load}>Try again</button>
        </Card>
      )}

      {issued && (
        <Card
          title="Their login"
          note="This is the only time the password is readable. Nothing stores it."
          actions={<button className="btn btn-quiet" onClick={() => setIssued(null)}>Done</button>}
        >
          <div className="creds">
            <div>
              <span className="creds-label">Login ID</span>
              <span className="creds-value mono">{issued.loginId}</span>
            </div>
            <div>
              <span className="creds-label">Password</span>
              <span className="creds-value mono">{issued.password}</span>
            </div>
          </div>
          <p className="small text-muted mb-0">
            {issued.name} is asked to choose their own the moment they sign in
            {issued.expiresInDays > 0
              ? `, and this one stops working if the account is unused for ${issued.expiresInDays} days.`
              : '.'}
            {' '}They can sign in with the login ID or with their email.
          </p>
        </Card>
      )}

      {showEnrol && (
        <Card
          title="Enrol a learner"
          note="Same path as the sheet import, so the rules cannot drift apart."
        >
          <div className="row g-3">
            <div className="col-md-4">
              <label className="form-label" htmlFor="en-name">Full name</label>
              <input id="en-name" className="form-control" value={enrol.fullName}
                onChange={set('fullName')} placeholder="Priya Sharma" />
            </div>
            <div className="col-md-4">
              <label className="form-label" htmlFor="en-email">Email</label>
              <input id="en-email" className="form-control" type="email" value={enrol.email}
                onChange={set('email')} placeholder="priya@example.com" />
            </div>
            <div className="col-md-2">
              <label className="form-label" htmlFor="en-phone">Phone</label>
              <input id="en-phone" className="form-control" value={enrol.phone} onChange={set('phone')} />
            </div>
            <div className="col-md-2">
              <label className="form-label" htmlFor="en-wa">WhatsApp</label>
              <input id="en-wa" className="form-control" value={enrol.whatsapp}
                onChange={set('whatsapp')} placeholder="If different" />
            </div>

            <div className="col-md-4">
              <label className="form-label" htmlFor="en-course">Course</label>
              <select id="en-course" className="form-select" value={enrol.bundleId} onChange={set('bundleId')}>
                <option value="">Choose a course</option>
                {courses.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}{c.published ? '' : ' (draft)'}
                  </option>
                ))}
              </select>
              {courses.length === 0 && (
                <div className="small text-muted mt-1">
                  No courses yet. Build one under Courses first: without one, every video
                  answers that it is not part of their course.
                </div>
              )}
            </div>

            <div className="col-md-2">
              <label className="form-label" htmlFor="en-track">Track</label>
              <select id="en-track" className="form-select" value={enrol.trackType} onChange={set('trackType')}>
                <option value="PREMIUM">Premium</option>
                <option value="BATCH">Batch</option>
              </select>
            </div>

            {/* a batch learner without a batch is placed by the Tuesday rule, which is
                usually what you want; a premium learner has no batch at all */}
            {enrol.trackType === 'BATCH' && (
              <div className="col-md-3">
                <label className="form-label" htmlFor="en-batch">Batch</label>
                <select id="en-batch" className="form-select" value={enrol.batchId} onChange={set('batchId')}>
                  <option value="">Place them automatically</option>
                  {batches.map((b) => (
                    <option key={b.id} value={b.id}>{b.code} · starts {b.startDate}</option>
                  ))}
                </select>
                <div className="small text-muted mt-1">
                  Left blank, they join the batch that started on or before their joining date.
                </div>
              </div>
            )}

            <div className="col-md-3">
              <label className="form-label" htmlFor="en-mentor">Mentor</label>
              <select id="en-mentor" className="form-select" value={enrol.mentorId} onChange={set('mentorId')}>
                <option value="">Assign later</option>
                {mentors.map((m) => (
                  <option key={m.id} value={m.id}>{m.name} · {m.learners} learners</option>
                ))}
              </select>
            </div>

            <div className="col-md-2">
              <label className="form-label" htmlFor="en-joined">Joined on</label>
              <input id="en-joined" className="form-control" type="date" value={enrol.joinedOn}
                onChange={set('joinedOn')} />
            </div>

            {preview && (
              <div className="col-12">
                <div className="creds creds-preview">
                  <div>
                    <span className="creds-label">Login ID will be</span>
                    <span className="creds-value mono">{preview.loginId}</span>
                  </div>
                  <div>
                    <span className="creds-label">Password will be</span>
                    <span className="creds-value mono">{preview.password}</span>
                  </div>
                </div>
              </div>
            )}

            <div className="col-12 d-flex gap-2 align-items-center flex-wrap">
              <button className="btn btn-pib" onClick={submitEnrol}
                disabled={!enrol.email || !enrol.fullName || !enrol.bundleId}>
                Create account and mail the login
              </button>
              <button className="btn btn-quiet" onClick={() => setShowEnrol(false)}>Cancel</button>
              <span className="small text-muted">
                They can sign in with either the login ID or the email.
              </span>
            </div>
          </div>
        </Card>
      )}

      <Card
        title="Learners"
        actions={
          <>
            <select className="form-select" style={{ width: 150 }} value={track} onChange={(e) => setTrack(e.target.value)}>
              <option value="">All tracks</option>
              <option value="PREMIUM">Premium</option>
              <option value="BATCH">Batch</option>
            </select>
            <input className="form-control" style={{ width: 220 }} placeholder="Search"
              value={q} onChange={(e) => setQ(e.target.value)} />
          </>
        }
      >
        {rows.length === 0 ? <Empty title="No learners match that" /> : (
          <div className="table-responsive">
            <table className="table table-pib mb-0">
              <thead><tr><th>Learner</th><th>Track</th><th>Batch</th><th>Course</th><th>Progress</th><th>Onboarding</th><th>Mentor</th><th /></tr></thead>
              <tbody>
                {rows.map((l) => (
                  <tr key={l.learnerId}>
                    <td>
                      <span className="who-cell">
                        <Avatar name={l.name} size={34} track={l.trackType} />
                        <span>
                          <Link to={`/mentor/learners/${l.learnerId}`}>{l.name}</Link>
                          <div className="mono" style={{ fontSize: '.72rem', color: 'var(--ink-30)' }}>
                            {l.loginId ? `${l.loginId} · ${l.email}` : l.email}
                          </div>
                        </span>
                      </span>
                    </td>
                    <td><TrackTag type={l.trackType} /></td>
                    <td className="mono">{l.batch || '\u2014'}</td>
                    <td>{l.bundle || '\u2014'}</td>
                    <td className="mono">{l.percent}%</td>
                    <td>{l.onboarded ? <Tag kind="ok">Done</Tag> : <Tag kind="wait">Open</Tag>}</td>
                    <td style={{ whiteSpace: 'nowrap' }}>
                      {l.mentor
                        ? <span>{l.mentor}</span>
                        : <span className="text-muted small">Not assigned</span>}
                      <button className="btn btn-s ms-2" onClick={() => changeMentor(l)}>
                        {l.mentor ? 'Change' : 'Assign'}
                      </button>
                    </td>
                    <td className="text-end">
                      <div className="row-actions">
                        <button className="btn btn-s" onClick={() => resend(l.learnerId)}>Resend login</button>
                        <button className="btn btn-s" onClick={() => resetPassword(l)}>Reset password</button>
                      </div>
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
