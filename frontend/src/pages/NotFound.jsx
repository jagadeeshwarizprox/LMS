import { Link } from 'react-router-dom'
import Brand from '../components/Brand'

/**
 * A wrong address used to bounce silently to the roadmap, which looks like the app
 * ignored you. Saying what happened costs one screen.
 */
export default function NotFound() {
  return (
    <div className="auth-wrap">
      <div className="auth-card">
        <Brand />
        <h2 className="mb-1">That page is not here</h2>
        <p className="text-muted mb-4" style={{ fontSize: '.87rem' }}>
          The link may be old, or the thing it pointed at has moved. Nothing is broken.
        </p>
        <Link className="btn btn-pib w-100" to="/">Back to the start</Link>
      </div>
    </div>
  )
}
