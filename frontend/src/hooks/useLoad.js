import { useCallback, useEffect, useState } from 'react'

/**
 * Load once, keep the error, and be able to try again.
 *
 * Most pages here were written as `api.get(x).then(setState)` with no catch, which has
 * one failure mode and it is the worst one: state stays null, the skeleton never
 * resolves, and the person sits looking at a shimmering placeholder with nothing
 * telling them anything went wrong. Reported as "it does not load", which is exactly
 * what it does.
 *
 * Returns the data, the error, a loading flag and a reload, so a page can render an
 * honest failure with a way out of it.
 */
export default function useLoad(fn, deps = []) {
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(true)

  const run = useCallback(async () => {
    setLoading(true)
    try {
      setData(await fn())
      setError(null)
    } catch (e) {
      setError(e.message || 'That did not load.')
    } finally {
      setLoading(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)

  useEffect(() => { run() }, [run])

  return { data, error, loading, reload: run, setData }
}
