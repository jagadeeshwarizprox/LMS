import { NavLink, useLocation } from 'react-router-dom'
import { useState } from 'react'
import { useAuth } from '../context/AuthContext'
import Icon from './Icon'

/**
 * A bottom bar on a phone.
 *
 * A 74px rail and a slide-out sidebar are desktop patterns. On a phone the thumb reaches
 * the bottom of the screen and nothing else, which is why every app people already use
 * puts navigation there.
 *
 * Four destinations, because five is where a bar starts to feel like a menu. Everything
 * else lives behind More, which opens a sheet rather than another screen.
 */
const PRIMARY = {
  /* four thumbs on the bar, and each one is a different kind of thing: what to study,
     when to turn up, what to hand in, what was briefed */
  LEARNER: [
    ['/learn', 'Roadmap', 'roadmap'],
    ['/learn/sessions', 'Sessions', 'sessions'],
    ['/learn/projects', 'Tasks', 'queues'],
    ['/learn/project-work', 'Project', 'projects']
  ],
  MENTOR: [
    ['/mentor', 'Desk', 'dashboard'],
    ['/mentor/tasks', 'Review', 'queues'],
    ['/mentor/learners', 'Learners', 'batch'],
    ['/week', 'Week', 'sessions']
  ],
  ADMIN: [
    ['/admin', 'Overview', 'dashboard'],
    ['/admin/onboarding', 'Onboarding', 'onboarding'],
    ['/admin/register', 'Learners', 'batch'],
    ['/week', 'Week', 'sessions']
  ],
  SUPER_ADMIN: [
    ['/super', 'Overview', 'dashboard'],
    ['/super/modules', 'Content', 'catalogue'],
    ['/analytics', 'Analytics', 'roadmap'],
    ['/super/settings', 'Settings', 'settings']
  ]
}

const MORE = {
  LEARNER: [
    ['/learn/batch', 'My batch', 'batch'],
    ['/learn/mock', 'Mock interviews', 'mock'],
    ['/learn/resources', 'Jobs and case studies', 'resources'],
    ['/learn/profile', 'My profile', 'profile']
  ],
  MENTOR: [
    ['/mentor/projects', 'Project reviews', 'projects'],
    ['/mentor/recordings', 'Sessions to publish', 'recordings'],
    ['/mentor/at-risk', 'At risk', 'risk'],
    ['/mentor/batches', 'My batches', 'batches'],
    ['/mentor/attendance', 'Attendance and notes', 'people'],
    ['/analytics', 'Analytics', 'roadmap'],
    ['/week/repeating', 'Repeating sessions', 'slots'],
    ['/mentor/slots', 'My one to ones', 'slots'],
    ['/mentor/team', 'My team', 'people']
  ],
  ADMIN: [
    ['/admin/batches', 'Batches', 'batches'],
    ['/analytics', 'Analytics', 'roadmap'],
    ['/admin/projects', 'Projects', 'projects'],
    ['/admin/mentors', 'Mentors', 'people'],
    ['/admin/cover', 'Mentor cover', 'people'],
    ['/admin/import', 'Import from the sheet', 'import'],
    ['/admin/mail', 'Credential mail log', 'mail']
  ],
  SUPER_ADMIN: [
    ['/super/courses', 'Courses and pricing', 'library'],
    ['/super/projects', 'Projects', 'projects'],
    ['/week', 'Weekly sessions', 'sessions'],
    ['/super/people', 'Admin and mentors', 'people'],
    ['/super/form', 'Information form', 'intake'],
    ['/super/faqs', 'Help and FAQs', 'faqs'],
    ['/super/activity', 'Activity log', 'activity']
  ]
}

export default function MobileNav() {
  const { user, signOut } = useAuth()
  const { pathname } = useLocation()
  const [more, setMore] = useState(false)
  if (!user) return null

  const primary = PRIMARY[user.role] || []
  const rest = MORE[user.role] || []

  return (
    <>
      <nav className="mnav" aria-label="Main">
        {primary.map(([to, label, icon]) => (
          <NavLink
            key={to}
            to={to}
            end={to === '/learn' || to === '/mentor' || to === '/admin' || to === '/super'}
            className={({ isActive }) => `mnav-item ${isActive ? 'on' : ''}`}
          >
            <Icon name={icon} size={19} />
            <span>{label}</span>
          </NavLink>
        ))}
        <button
          className={`mnav-item ${more ? 'on' : ''}`}
          onClick={() => setMore(true)}
          aria-expanded={more}
        >
          <Icon name="menu" size={19} />
          <span>More</span>
        </button>
      </nav>

      {more && (
        <div className="msheet-backdrop" onClick={(e) => e.target === e.currentTarget && setMore(false)}>
          <div className="msheet" role="dialog" aria-label="More">
            <div className="msheet-grip" />
            <div className="msheet-list">
              {rest.map(([to, label, icon]) => (
                <NavLink
                  key={to}
                  to={to}
                  className={`msheet-row ${pathname === to ? 'on' : ''}`}
                  onClick={() => setMore(false)}
                >
                  <Icon name={icon} size={17} />
                  {label}
                </NavLink>
              ))}
              <button
                className="msheet-row"
                onClick={async () => { setMore(false); await signOut() }}
              >
                <Icon name="signout" size={17} />
                Sign out
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
