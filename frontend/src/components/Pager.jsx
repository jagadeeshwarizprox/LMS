import { useCallback, useEffect, useRef, useState } from 'react'

/*
 * Paging for the big admin lists.
 *
 * The register, the onboarding board, the activity log, the playback log and the mail
 * log all used to fetch everything (or the latest hundred) and filter in the browser.
 * At twenty thousand learners that is either a page that never finishes loading or a
 * list that silently stops at an arbitrary row. These lists now ask the server for one
 * page of fifty with the filters applied there, and all of them answer in one shape:
 * { rows, total, page, size }, page counted from zero.
 *
 * Three pieces, used together:
 *   useDebounced  - a typed search only goes to the server after 300ms of quiet
 *   usePagedList  - asks for filters&page&size, back to page 0 whenever a filter
 *                   changes, and keeps the filters when you page
 *   Pager         - prev / next and "Showing 51-100 of 20,000"
 */

/** The value, once it has stopped changing for `ms`. */
export function useDebounced(value, ms = 300) {
  const [settled, setSettled] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => setSettled(value), ms)
    return () => clearTimeout(t)
  }, [value, ms])
  return settled
}

/** k=v&... with blank, null and undefined values left out. */
export function queryString(params) {
  const qs = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v === undefined || v === null || v === '') return
    qs.set(k, String(v))
  })
  return qs.toString()
}

/**
 * One paged list.
 *
 * `fetchPage` is handed the query string and returns the request, for example
 * `(qs) => api.get(`/admin/register?${qs}`)`. The call is written at the page rather
 * than built in here so every endpoint stays visible as a literal api.get where it is
 * used, which is what tools/check-wiring.py reads to match screens to routes.
 *
 * `filters` is a plain object; any change to it (compared by value) sends the list back
 * to the first page, because page 7 of the old filter is meaningless under the new one.
 * A response that arrives after a newer request was made is dropped, so typing quickly
 * cannot leave the list showing the answer to an earlier search.
 */
export function usePagedList(fetchPage, filters, size = 50) {
  /* the latest function, without making every render a new request */
  const fetchRef = useRef(fetchPage)
  fetchRef.current = fetchPage
  const key = JSON.stringify(filters)
  /* the page remembers which filters it belongs to; under any other filters it is 0 */
  const [at, setAt] = useState({ key, page: 0 })
  const page = at.key === key ? at.page : 0
  const setPage = useCallback((p) => setAt({ key, page: Math.max(0, p) }), [key])
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)
  const latest = useRef(0)

  const load = useCallback(() => {
    const ticket = ++latest.current
    setLoading(true)
    return fetchRef.current(queryString({ ...JSON.parse(key), page, size }))
      .then((r) => {
        if (ticket !== latest.current) return
        setData(r)
        setError(null)
      })
      .catch((e) => { if (ticket === latest.current) setError(e.message) })
      .finally(() => { if (ticket === latest.current) setLoading(false) })
  }, [key, page, size])

  useEffect(() => { load() }, [load])

  return {
    data,
    rows: data?.rows || [],
    total: data?.total ?? 0,
    page,
    size,
    setPage,
    loading,
    error,
    reload: load
  }
}

/** "Showing 51-100 of 20,000" with previous and next. Renders nothing for one short page. */
export default function Pager({ page, size, total, onPage, loading = false, noun = '' }) {
  if (!total || total <= 0) return null
  const from = page * size + 1
  const to = Math.min(total, (page + 1) * size)
  const last = Math.max(0, Math.ceil(total / size) - 1)
  const fmt = (n) => n.toLocaleString('en-IN')
  if (last === 0 && page === 0) {
    return (
      <div className="small muted mt-3" aria-live="polite">
        Showing all {fmt(total)}{noun ? ` ${noun}` : ''}
      </div>
    )
  }
  return (
    <nav className="d-flex align-items-center justify-content-between gap-2 flex-wrap mt-3"
      aria-label="Pages">
      <span className="small muted" aria-live="polite">
        Showing {fmt(from)}-{fmt(to)} of {fmt(total)}{noun ? ` ${noun}` : ''}
        {loading ? ' ...' : ''}
      </span>
      <span className="d-flex gap-2">
        <button className="btn btn-quiet btn-sm" disabled={page <= 0 || loading}
          onClick={() => onPage(page - 1)}>
          Previous
        </button>
        <span className="small mono align-self-center">{fmt(page + 1)} / {fmt(last + 1)}</span>
        <button className="btn btn-quiet btn-sm" disabled={page >= last || loading}
          onClick={() => onPage(page + 1)}>
          Next
        </button>
      </span>
    </nav>
  )
}
