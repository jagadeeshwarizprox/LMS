import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '../api/client'
import Brand from '../components/Brand'
import Icon from '../components/Icon'
import { useToast } from '../context/ToastContext'

/**
 * Asking for a link.
 *
 * The answer is the same whether the address has an account or not. Anything else turns
 * this form into a way of finding out which addresses are real, and the person who
 * genuinely forgot their password is no worse off for the ambiguity.
 */
export default function ForgotPassword() {
  const toast = useToast()
  const [params] = useSearchParams()
  const staff = params.get('staff') === '1'
  const [email, setEmail] = useState('')
  const [sent, setSent] = useState(null)
  const [busy, setBusy] = useState(false)

  const submit = async (e) => {
    e.preventDefault()
    setBusy(true)
    try {
      const r = await api.post('/auth/forgot', { email })
      setSent(r.message)
    } catch (err) {
      toast.push(err.message, 'bad')
    } finally { setBusy(false) }
  }

  return (
    <div className={`auth-wrap ${staff ? 'staff' : ''}`}>
      <div className={`auth-card ${staff ? 'staff-card' : ''}`}>
        <Brand onDark={staff} />
        <h2 className="mb-1">Forgot your password</h2>

        {sent ? (
          <>
            <div className="reset-done">
              <Icon name="check" size={18} />
              <p className="mb-0">{sent}</p>
            </div>
            <p className="small muted mt-3">
              Check spam if it does not arrive. The link works once, and asking again
              cancels the last one.
            </p>
          </>
        ) : (
          <>
            <p className="text-muted mb-4" style={{ fontSize: '.87rem' }}>
              Put in the address you sign in with. If it has an account, a link comes back.
            </p>
            <form onSubmit={submit}>
              <div className="mb-4">
                <label className="form-label" htmlFor="email">Login ID</label>
                <input
                  id="email"
                  className="form-control"
                  type="email"
                  autoComplete="username"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  required
                />
              </div>
              <button className="btn btn-pib w-100" disabled={busy}>
                {busy ? 'Sending' : 'Send me a link'}
              </button>
            </form>
          </>
        )}

        <div className={`auth-switch ${staff ? 'staff' : ''}`}>
          <Link to={staff ? '/staff' : '/'}>Back to sign in</Link>
        </div>
      </div>
    </div>
  )
}
