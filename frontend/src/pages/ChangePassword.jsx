import { useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import Brand from '../components/Brand'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'

const HOME = { LEARNER: '/learn', MENTOR: '/mentor', ADMIN: '/admin', SUPER_ADMIN: '/super' }

/** The generated password is replaced before anything else in the LMS opens. */
export default function ChangePassword() {
  const { user, changePassword } = useAuth()
  const toast = useToast()
  const navigate = useNavigate()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [again, setAgain] = useState('')
  const [busy, setBusy] = useState(false)

  if (!user) return <Navigate to="/" replace />
  if (!user.mustChangePassword) return <Navigate to={HOME[user.role]} replace />

  const submit = async (e) => {
    e.preventDefault()
    if (next !== again) {
      toast.push('The two new passwords do not match.', 'bad')
      return
    }
    setBusy(true)
    try {
      const updated = await changePassword(current, next)
      toast.push('Password set. Welcome in.')
      navigate(HOME[updated.role])
    } catch (err) {
      toast.push(err.message, 'bad')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="auth-wrap">
      <div className="auth-card">
        <Brand />
        <h2 className="mb-1">Set your password</h2>
        <p className="text-muted mb-4" style={{ fontSize: '.87rem' }}>
          Replace the password we mailed you. This is the last step before your LMS opens.
        </p>
        <form onSubmit={submit}>
          <div className="mb-3">
            <label className="form-label">Password we mailed you</label>
            <input className="form-control" type="password" value={current}
              onChange={(e) => setCurrent(e.target.value)} required />
          </div>
          <div className="mb-3">
            <label className="form-label">New password</label>
            <input className="form-control" type="password" minLength={8} value={next}
              onChange={(e) => setNext(e.target.value)} required />
            <div className="text-muted mt-1" style={{ fontSize: '.78rem' }}>At least 8 characters.</div>
          </div>
          <div className="mb-4">
            <label className="form-label">New password again</label>
            <input className="form-control" type="password" value={again}
              onChange={(e) => setAgain(e.target.value)} required />
          </div>
          <button className="btn btn-pib w-100" disabled={busy}>
            {busy ? 'Saving' : 'Save and continue'}
          </button>
        </form>
      </div>
    </div>
  )
}
