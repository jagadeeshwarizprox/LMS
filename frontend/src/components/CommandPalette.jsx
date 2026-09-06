import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import { useAuth } from '../context/AuthContext'
import Avatar from './Avatar'
import Icon from './Icon'

/**
 * Cmd-K, and everything is one search away.
 *
 * A mentor with forty learners should not navigate to a learner by remembering which
 * cohort they are in and scrolling. Typing three letters of their name is how every tool
 * a person already uses works, and it costs one keystroke to learn.
 *
 * Everything searchable is loaded once when the palette first opens, not on every
 * keystroke: the sets are small, and a search that waits on the network stops feeling
 * like search.
 */

const SCREENS = {
  LEARNER: [
    ['My roadmap', '/learn', 'roadmap'],
    ['Sessions', '/learn/sessions', 'sessions'],
    ['Tasks', '/learn/projects', 'queues'],
    ['My project', '/learn/project-work', 'projects'],
    ['Recorded sessions', '/learn/recordings', 'recordings'],
    ['Mock interviews', '/learn/mock', 'mock'],
    ['My batch', '/learn/batch', 'batch'],
    ['Information form', '/learn/intake', 'intake'],
    ['My study time', '/learn/time', 'time'],
    ['My profile', '/learn/profile', 'profile']
  ],
  MENTOR: [
    ['My desk', '/mentor', 'dashboard'],
    ['My learners', '/mentor/learners', 'batch'],
    ['Tasks', '/mentor/tasks', 'projects'],
    ['At risk', '/mentor/at-risk', 'risk'],
    ['Attendance and notes', '/mentor/attendance', 'people'],
    ['Sessions to publish', '/mentor/recordings', 'recordings'],
    ['Weekly sessions', '/week', 'sessions'],
    ['Repeating sessions', '/week/repeating', 'slots'],
    ['My one to ones', '/mentor/slots', 'slots'],
    ['My batches', '/mentor/batches', 'batches'],
    ['My team', '/mentor/team', 'people'],
    ['Project reviews', '/mentor/projects', 'projects'],
    ['Analytics', '/analytics', 'roadmap']
  ],
  ADMIN: [
    ['Overview', '/admin', 'dashboard'],
    ['Onboarding board', '/admin/onboarding', 'board'],
    ['All learners', '/admin/register', 'people'],
    ['Batches', '/admin/batches', 'batches'],
    ['Weekly sessions', '/week', 'sessions'],
    ['Enrol on a project', '/admin/projects', 'projects'],
    ['Project reviews', '/mentor/projects', 'projects'],
    ['Projects', '/super/projects', 'projects'],
    ['Cover', '/admin/cover', 'people'],
    ['Import from the sheet', '/admin/import', 'import'],
    ['Credential mail log', '/admin/mail', 'mail'],
    ['Mentors', '/admin/mentors', 'people'],
    ['Analytics', '/analytics', 'roadmap']
  ],
  /* the palette uses the same words as the menu, so what somebody types is what they
     read a moment ago rather than an older name for the same screen */
  SUPER_ADMIN: [
    ['Overview', '/super', 'dashboard'],
    ['Modules and chapters', '/super/modules', 'catalogue'],
    ['Courses and pricing', '/super/courses', 'library'],
    ['Projects', '/super/projects', 'projects'],
    ['Weekly sessions', '/week', 'sessions'],
    ['Admin and mentors', '/super/people', 'people'],
    ['Information form', '/super/form', 'intake'],
    ['Help and FAQs', '/super/faqs', 'faqs'],
    ['App settings', '/super/settings', 'settings'],
    ['Activity log', '/super/activity', 'activity'],
    ['Analytics', '/analytics', 'roadmap']
  ]
}

const RECENT_KEY = 'pib.recent'

/* the shortcut is written the way the person's own keyboard writes it */
const modKey = () =>
  (typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)) ? 'cmd' : 'ctrl'

export function rememberVisit(item) {
  try {
    const list = JSON.parse(localStorage.getItem(RECENT_KEY) || '[]')
      .filter((x) => x.to !== item.to)
    localStorage.setItem(RECENT_KEY, JSON.stringify([item, ...list].slice(0, 5)))
  } catch { /* storage off, not worth failing over */ }
}

export default function CommandPalette() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const [q, setQ] = useState('')
  const [cursor, setCursor] = useState(0)
  const [extra, setExtra] = useState([])
  const [loaded, setLoaded] = useState(false)
  const input = useRef(null)

  const staff = user && user.role !== 'LEARNER'

  useEffect(() => {
    const onKey = (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setOpen((v) => !v)
      }
      if (e.key === 'Escape') setOpen(false)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  useEffect(() => {
    if (!open) { setQ(''); setCursor(0); return }
    requestAnimationFrame(() => input.current?.focus())
    if (loaded || !user) return
    setLoaded(true)
    load()
  }, [open, user])

  const load = useCallback(async () => {
    const found = []
    try {
      if (staff) {
        const roster = await api.get('/mentor/roster').catch(() => null)
        roster?.groups?.forEach((g) => g.learners.forEach((l) => found.push({
          kind: 'Learner', label: l.name, note: `${g.label} · ${l.percent}%`,
          to: `/mentor/learners/${l.learnerId}`, avatar: l.name, track: l.trackType
        })))
      } else {
        const dash = await api.get('/learner/dashboard').catch(() => null)
        dash?.roadmap?.forEach((t) => t.chapters?.forEach((c) => found.push({
          kind: 'Topic', label: c.title, note: t.name,
          to: `/learn/chapter/${c.chapterId || c.id}`, icon: 'roadmap'
        })))
      }
    } catch { /* the palette still works with screens alone */ }
    setExtra(found)
  }, [staff])

  const screens = useMemo(() => {
    const roles = user
      ? user.role === 'SUPER_ADMIN' ? ['SUPER_ADMIN', 'ADMIN', 'MENTOR']
        : user.role === 'ADMIN' ? ['ADMIN', 'MENTOR']
        : [user.role]
      : []
    return roles.flatMap((r) => (SCREENS[r] || []).map(([label, to, icon]) => ({
      kind: 'Go to', label, to, icon
    })))
  }, [user])

  const recent = useMemo(() => {
    if (q) return []
    try {
      return JSON.parse(localStorage.getItem(RECENT_KEY) || '[]')
        .map((x) => ({ ...x, kind: 'Recent' }))
    } catch { return [] }
  }, [q, open])

  const results = useMemo(() => {
    const all = [...screens, ...extra]
    if (!q.trim()) return [...recent, ...screens].slice(0, 9)
    const needle = q.toLowerCase()
    return all
      .map((r) => {
        const label = r.label.toLowerCase()
        /* a word-start match beats a match buried in the middle, which is what makes
           typing two letters feel like it read your mind */
        const score = label.startsWith(needle) ? 0
          : label.split(/\s+/).some((w) => w.startsWith(needle)) ? 1
          : label.includes(needle) ? 2
          : (r.note || '').toLowerCase().includes(needle) ? 3 : 99
        return { ...r, score }
      })
      .filter((r) => r.score < 99)
      .sort((a, b) => a.score - b.score)
      .slice(0, 9)
  }, [q, screens, extra, recent])

  useEffect(() => { setCursor(0) }, [q])

  if (!open || !user) return null

  const choose = (r) => {
    if (!r) return
    rememberVisit({ label: r.label, to: r.to, icon: r.icon, avatar: r.avatar })
    setOpen(false)
    navigate(r.to)
  }

  const onKeyDown = (e) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setCursor((c) => Math.min(c + 1, results.length - 1)) }
    if (e.key === 'ArrowUp') { e.preventDefault(); setCursor((c) => Math.max(c - 1, 0)) }
    if (e.key === 'Enter') { e.preventDefault(); choose(results[cursor]) }
  }

  return (
    <div className="cmd-backdrop" onClick={(e) => e.target === e.currentTarget && setOpen(false)}>
      <div className="cmd" role="dialog" aria-label="Search and jump">
        <div className="cmd-head">
          <Icon name="search" size={17} />
          <input
            ref={input}
            className="cmd-input"
            value={q}
            placeholder={staff ? 'Search learners, screens' : 'Search topics, screens'}
            onChange={(e) => setQ(e.target.value)}
            onKeyDown={onKeyDown}
          />
          <kbd className="cmd-esc">esc</kbd>
        </div>

        {results.length === 0 ? (
          <div className="cmd-empty">Nothing matches that.</div>
        ) : (
          <div className="cmd-list">
            {results.map((r, i) => (
              <button
                key={`${r.to}-${i}`}
                className={`cmd-row ${i === cursor ? 'on' : ''}`}
                onMouseEnter={() => setCursor(i)}
                onClick={() => choose(r)}
              >
                {r.avatar
                  ? <Avatar name={r.avatar} size={26} track={r.track} />
                  : <span className="cmd-icon"><Icon name={r.icon || 'roadmap'} size={15} /></span>}
                <span className="cmd-label">
                  {r.label}
                  {r.note && <span className="cmd-note">{r.note}</span>}
                </span>
                <span className="cmd-kind">{r.kind}</span>
              </button>
            ))}
          </div>
        )}

        <div className="cmd-foot">
          <span><kbd>up</kbd><kbd>down</kbd> to move</span>
          <span><kbd>enter</kbd> to open</span>
          <span className="ms-auto"><kbd>{modKey()}</kbd><kbd>K</kbd> from anywhere</span>
        </div>
      </div>
    </div>
  )
}
