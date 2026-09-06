import { useState } from 'react'
import { Link } from 'react-router-dom'
import Brand from '../components/Brand'
import FaqBlock from '../components/FaqBlock'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'

/**
 * Where learners sign in.
 *
 * Warm, and it answers the questions people actually arrive with: no mail, forgotten
 * password, too many devices. Staff have their own page, so nothing here has to hedge
 * between two audiences.
 */
export default function LearnerLogin() {
  const { signIn } = useAuth()
  const toast = useToast()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async (e) => {
    e.preventDefault()
    setBusy(true)
    try {
      await signIn(email, password, 'LEARNER')
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
        <h2 className="mb-1">Welcome back</h2>
        <p className="text-muted mb-4" style={{ fontSize: '.87rem' }}>
          Use the login ID from your enrolment mail. Accounts are created by the team,
          so there is nothing to register.
        </p>
        <form onSubmit={submit}>
          <div className="mb-3">
            <label className="form-label" htmlFor="email">Login ID or email</label>
            <input
              id="email"
              className="form-control"
              type="text"
              autoComplete="username"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="priya.sharma, or the email on your enrolment"
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
          <Link to="/forgot">Forgot your password?</Link>
        </div>

        <div className="small muted mt-3">
          One account, one learner. Signing in here ends your session on any other device.
        </div>

        <FaqBlock publicMode title="Trouble signing in" />

        <div className="auth-switch">
          Mentor or admin? <Link to="/staff">Sign in here</Link>
        </div>
      </div>
    </div>
  )
}
