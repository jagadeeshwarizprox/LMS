import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { api } from '../api/client'
import Brand from '../components/Brand'
import { useToast } from '../context/ToastContext'

/**
 * Setting the new password.
 *
 * The link is checked before the form appears, so nobody types a password into something
 * already expired. Using it signs out every device, because a reset usually means
 * somebody else had the account.
 */
export default function ResetPassword() {
  const toast = useToast()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const token = params.get('token') || ''

  const [state, setState] = useState(null)
  const [password, setPassword] = useState('')
  const [again, setAgain] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api.get(`/auth/reset?token=${encodeURIComponent(token)}`)
      .then(setState)
      .catch(() => setState({ valid: false, message: 'That link cannot be used.' }))
  }, [token])

  if (!state) return null

  const tooShort = password.length > 0 && password.length < 8
  const mismatch = again.length > 0 && password !== again
  const ready = password.length >= 8 && password === again && !busy

  const submit = async (e) => {
    e.preventDefault()
    if (!ready) return
    setBusy(true)
    try {
      const r = await api.post('/auth/reset', { token, password })
      toast.push(r.message)
      navigate('/')
    } catch (err) {
      toast.push(err.message, 'bad')
    } finally { setBusy(false) }
  }

  return (
    <div className="auth-wrap">
      <div className="auth-card">
        <Brand />
        {!state.valid ? (
          <>
            <h2 className="mb-1">This link will not work</h2>
            <p className="text-muted mb-4" style={{ fontSize: '.87rem' }}>{state.message}</p>
            <Link className="btn btn-pib w-100" to="/forgot">Ask for a new one</Link>
          </>
        ) : (
          <>
            <h2 className="mb-1">Set a new password</h2>
            <p className="text-muted mb-4" style={{ fontSize: '.87rem' }}>
              {state.name ? `Hello ${state.name.split(' ')[0]}. ` : ''}
              Every device signed in on this account will be signed out.
            </p>
            <form onSubmit={submit}>
              <div className="mb-3">
                <label className="form-label" htmlFor="pw">New password</label>
                <input
                  id="pw"
                  className={`form-control ${tooShort ? 'is-bad' : ''}`}
                  type="password"
                  autoComplete="new-password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  required
                />
                <div className="small muted mt-1">
                  {tooShort ? 'At least eight characters.' : 'Eight characters or more.'}
                </div>
              </div>
              <div className="mb-4">
                <label className="form-label" htmlFor="pw2">Again</label>
                <input
                  id="pw2"
                  className={`form-control ${mismatch ? 'is-bad' : ''}`}
                  type="password"
                  autoComplete="new-password"
                  value={again}
                  onChange={(e) => setAgain(e.target.value)}
                  required
                />
                {mismatch && <div className="small mt-1" style={{ color: 'var(--warn)' }}>
                  These do not match.
                </div>}
              </div>
              <button className="btn btn-pib w-100" disabled={!ready}>
                {busy ? 'Setting' : 'Set password'}
              </button>
            </form>
          </>
        )}
      </div>
    </div>
  )
}
