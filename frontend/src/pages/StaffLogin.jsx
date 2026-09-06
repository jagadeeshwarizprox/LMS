import { useState } from 'react'
import { Link } from 'react-router-dom'
import Brand from '../components/Brand'
import Icon from '../components/Icon'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'

/**
 * Where mentors, admins and super admins sign in.
 *
 * Deliberately plainer than the learner page and on a dark surface, so nobody arrives
 * here by accident and nobody on a shared screen mistakes one for the other. No help
 * block: staff have colleagues, not a help centre.
 */
export default function StaffLogin() {
  const { signIn } = useAuth()
  const toast = useToast()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async (e) => {
    e.preventDefault()
    setBusy(true)
    try {
      await signIn(email, password, 'STAFF')
    } catch (err) {
      toast.push(err.message, 'bad')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="auth-wrap staff">
      <div className="auth-card staff-card">
        <Brand onDark />
        <div className="staff-badge">
          <Icon name="security" size={14} /> Team sign in
        </div>
        <h2 className="mb-1">ProITBridge team</h2>
        <p className="mb-4" style={{ fontSize: '.87rem', color: 'rgba(255,255,255,.6)' }}>
          Mentors, admins and super admins. Learner accounts do not sign in here.
        </p>
        <form onSubmit={submit}>
          <div className="mb-3">
            <label className="form-label" htmlFor="email">Login ID or work email</label>
            <input
              id="email"
              className="form-control"
              type="text"
              autoComplete="username"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="priya.sharma, or you@proitbridge.com"
              required
            />
          </div>
          <div className="mb-4">
            <label className="form-label" htmlFor="password">Password</label>
            <input
              id="password"
              className="form-control"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </div>
          <button className="btn btn-pib w-100" disabled={busy}>
            {busy ? 'Signing in' : 'Sign in'}
          </button>
        </form>

        <div className="auth-forgot">
          <Link to="/forgot?staff=1">Forgot your password?</Link>
        </div>

        <div className="small mt-3" style={{ color: 'rgba(255,255,255,.45)' }}>
          Everything you do here is recorded against your name in the activity log.
        </div>

        <div className="auth-switch staff">
          Are you a learner? <Link to="/">Sign in here</Link>
        </div>
      </div>
    </div>
  )
}
