import { useEffect, useState } from 'react'
import Icon from './Icon'

/**
 * Losing the connection used to show a failed request and nothing else, so it read
 * as the app breaking. This says which of the two it is.
 */
export default function Offline() {
  const [off, setOff] = useState(!navigator.onLine)
  useEffect(() => {
    const on = () => setOff(false)
    const gone = () => setOff(true)
    window.addEventListener('online', on)
    window.addEventListener('offline', gone)
    return () => {
      window.removeEventListener('online', on)
      window.removeEventListener('offline', gone)
    }
  }, [])
  if (!off) return null
  return (
    <div className="offline" role="status">
      <Icon name="ban" size={15} />
      You are offline. Anything you do now will not be saved.
    </div>
  )
}
