import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { useTheme } from '../context/ThemeContext'
import { useLearner } from '../context/LearnerContext'
import Brand from './Brand'
import SearchBox from './SearchBox'
import CheckIn from './CheckIn'
import Icon from './Icon'
import MobileNav from './MobileNav'
import Avatar from './Avatar'

/* [route, label, icon] — grouped the way the console groups them: what you
   control, what you author, what the system keeps, and the desks you run. */
const NAV = {
  /*
   * A learner is not an operator of this product. They are here to study, so the menu is
   * the shortest list that still reaches everything, and no two entries mean the same
   * thing.
   *
   * What changed and why:
   *   "Project" and "Tasks" sat next to each other with the same icon and near identical
   *   names. They are now My project (the briefed one, with stages) and Tasks (what a
   *   mentor sets on a chapter), with different icons.
   *
   *   Recorded sessions was its own entry for a list that only makes sense next to the
   *   sessions it recorded, so it is a tab inside Sessions.
   *
   *   Information form is a thing you do once during onboarding. It was a permanent menu
   *   item for the rest of the course; it now lives on the home checklist where the rest
   *   of onboarding is, and stays reachable by link.
   *
   *   Study time was a page for a number. It is on the profile.
   *
   * Entries carrying a `feature` are dropped when that learner's track does not include
   * them, so the menu never offers a page the server will refuse.
   */
  LEARNER: [
    ['Learning', [
      ['/learn', 'My roadmap', 'roadmap'],
      ['/learn/project-work', 'My project', 'projects'],
      ['/learn/projects', 'Tasks', 'queues'],
      ['/learn/mock', 'Mock interviews', 'mock']
    ]],
    ['Cohort', [
      ['/learn/sessions', 'Sessions', 'sessions'],
      /* a premium learner has no cohort, so My batch is not theirs to see */
      ['/learn/batch', 'My batch', 'batch', null, 'BATCH'],
      ['/learn/resources', 'Jobs and case studies', 'resources', ['jobs_referrals', 'case_studies']]
    ]],
    ['Me', [
      ['/learn/profile', 'My profile', 'profile']
    ]]
  ],
  /*
   * The staff menus share one vocabulary, on purpose.
   *
   * They used to group by department: Operations, Provisioning, Control, Run the day.
   * Those are org-chart words, so the same object appeared under a different heading
   * depending on who was looking at it, and two people describing the same screen used
   * two different names for where it lived.
   *
   * Four headings now, in the same order for every staff role, each answering a
   * different question:
   *
   *   Today     what is waiting on you right now
   *   Learners  the people and their records
   *   Teaching  the delivery machinery: sessions, projects, who teaches
   *   Setup     configuration and provisioning, touched rarely
   *
   * A role simply has fewer entries under a heading, or none. Nothing is filed by which
   * team owns it.
   */
  MENTOR: [
    ['Today', [
      ['/mentor', 'My desk', 'dashboard'],
      ['/mentor/tasks', 'Tasks to review', 'queues'],
      ['/mentor/projects', 'Project reviews', 'projects'],
      ['/mentor/recordings', 'Sessions to publish', 'recordings']
    ]],
    ['Learners', [
      ['/mentor/learners', 'My learners', 'batch'],
      ['/mentor/at-risk', 'At risk', 'risk'],
      ['/mentor/batches', 'My batches', 'batches'],
      ['/mentor/attendance', 'Attendance and notes', 'people'],
      ['/analytics', 'Analytics', 'roadmap']
    ]],
    ['Teaching', [
      ['/week', 'Weekly sessions', 'sessions'],
      ['/week/repeating', 'Repeating sessions', 'slots'],
      ['/mentor/slots', 'My one to ones', 'slots'],
      ['/mentor/team', 'My team', 'people']
    ]]
  ],
  /*
   * An admin runs the machine and is not a mentor.
   *
   * This used to carry a Mentoring section straight into the mentor desk, the task
   * queue and attendance, so an admin could score somebody else's learner's work. What
   * they own instead is everything around the teaching: the people, the batches, who
   * mentors whom, and what each learner has been granted. The mentor queues are still
   * reachable read only from the overview, because seeing a backlog is not the same as
   * clearing it.
   */
  ADMIN: [
    ['Today', [
      ['/admin', 'Overview', 'dashboard'],
      ['/admin/onboarding', 'Onboarding board', 'onboarding']
    ]],
    ['Learners', [
      ['/admin/register', 'All learners', 'batch'],
      ['/admin/batches', 'Batches', 'batches'],
      ['/super/features', 'Features and access', 'features'],
      ['/analytics', 'Analytics', 'roadmap']
    ]],
    ['Teaching', [
      ['/week', 'Weekly sessions', 'sessions'],
      ['/admin/projects', 'Projects', 'projects'],
      ['/admin/mentors', 'Mentors', 'people'],
      ['/admin/cover', 'Mentor cover', 'people']
    ]],
    ['Setup', [
      ['/admin/import', 'Import from the sheet', 'import'],
      ['/admin/mail', 'Credential mail log', 'mail']
    ]]
  ],
  /*
   * Super admin controls access and authors the product. It does not run a day of it.
   *
   * The learner register and the two role desks were here as a way in to somebody else's
   * screens, which blurred the line: this role decides what exists and who may reach it,
   * and the running of it belongs to the people whose job that is. Teaching here is the
   * catalogue, meaning what exists to be taught rather than this week's delivery of it.
   */
  SUPER_ADMIN: [
    ['Today', [
      ['/super', 'Overview', 'dashboard'],
      ['/analytics', 'Analytics', 'roadmap']
    ]],
    /* what a track includes is an operational decision the office makes week to week,
       so the toggles live with admin. Access here means who the staff are. */
    ['Access', [
      ['/super/people', 'Admin and mentors', 'people']
    ]],
    ['Teaching', [
      ['/super/modules', 'Modules and chapters', 'catalogue'],
      ['/super/courses', 'Courses and pricing', 'library'],
      ['/super/projects', 'Projects', 'projects'],
      ['/week', 'Weekly sessions', 'sessions']
    ]],
    ['Setup', [
      ['/super/form', 'Information form', 'intake'],
      ['/super/faqs', 'Help and FAQs', 'faqs'],
      ['/super/settings', 'App settings', 'settings'],
      ['/super/activity', 'Activity log', 'activity']
    ]]
  ]
}

const ROLE_LABEL = {
  LEARNER: 'Learner', MENTOR: 'Mentor', ADMIN: 'Admin', SUPER_ADMIN: 'Super Admin'
}

/**
 * Which menu entry a detail page belongs under.
 *
 * NavLink marks itself active on a prefix, so /super/courses/<id> lights up Courses on its
 * own. The chapter and learner record screens are the exceptions: they sit at their own top
 * level path with no menu entry above them, so opening one left the whole menu unlit and
 * nothing on screen said which section you were in. Editing a chapter test is exactly where
 * somebody is most likely to lose their place, so it is the one that mattered most.
 */
const DETAIL_PARENTS = [
  ['/super/chapters/', '/super/modules'],
  ['/super/modules/', '/super/modules'],
  ['/super/courses/', '/super/courses'],
  ['/mentor/learners/', '/mentor/learners'],
  ['/learn/chapter', '/learn']
]

function parentOf(pathname) {
  const hit = DETAIL_PARENTS.find(([prefix]) => pathname.startsWith(prefix))
  return hit ? hit[1] : null
}

/** The topbar says where you are, so the page header does not have to. */
function crumbFor(pathname, role) {
  for (const [section, links] of NAV[role] || []) {
    const hit = links.find(([to]) => to === pathname)
    if (hit) return [section, hit[1]]
  }
  if (pathname.startsWith('/learn/chapter')) return ['Learning', 'Chapter']
  if (pathname.startsWith('/super/chapters/')) return ['Content', 'Chapter']
  if (pathname.startsWith('/super/modules/')) return ['Content', 'Module']
  if (pathname.startsWith('/super/courses/')) return ['Content', 'Course']
  if (pathname.startsWith('/mentor/learners/')) return ['People', 'Learner record']
  return [null, null]
}

/** The globe from the logo, redrawn so the rail never shows a clipped wordmark. */
function RailMark() {
  const pts = [[18, 5], [30, 11], [33, 24], [24, 33], [11, 32], [4, 21], [6, 9], [18, 19]]
  const edges = [[7, 0], [0, 1], [1, 2], [2, 3], [3, 4], [4, 5], [5, 6], [6, 7], [7, 1], [7, 3], [7, 5]]
  return (
    <svg className="rail-mark" viewBox="0 0 38 38" aria-label="ProITBridge">
      <g stroke="rgba(0,176,240,.45)" strokeWidth="1">
        {edges.map((e, i) => (
          <line key={i} x1={pts[e[0]][0]} y1={pts[e[0]][1]} x2={pts[e[1]][0]} y2={pts[e[1]][1]} />
        ))}
      </g>
      {pts.map((p, i) => (
        <circle key={i} cx={p[0]} cy={p[1]} r={i === 7 ? 3 : 2} fill={i === 7 ? '#00B0F0' : '#38C4F5'} />
      ))}
    </svg>
  )
}

export default function AppShell({ children }) {
  const { user, signOut } = useAuth()
  /* staff have no learner context; `can` is then simply always false and no entry on
     their side carries a feature, so the filter is a no-op for them */
  const { can = () => false, learner } = useLearner() || {}
  const trackType = learner?.trackType
  const { dark, toggle } = useTheme()
  const navigate = useNavigate()
  const location = useLocation()

  const [open, setOpen] = useState(false)
  const [rail, setRail] = useState(() => localStorage.getItem('pib.rail') === '1')
  const [menu, setMenu] = useState(false)
  const [scrolled, setScrolled] = useState(false)

  /* declared before the pill effect below, which depends on it */
  const detailParent = parentOf(location.pathname)

  const navWrap = useRef(null)
  const account = useRef(null)
  const [pill, setPill] = useState({ top: 0, height: 36, shown: false })

  useEffect(() => { localStorage.setItem('pib.rail', rail ? '1' : '0') }, [rail])

  useLayoutEffect(() => {
    const el = navWrap.current?.querySelector('.nav-link.active')
    if (!el) { setPill((p) => ({ ...p, shown: false })); return }
    setPill({ top: el.offsetTop, height: el.offsetHeight, shown: true })
  }, [location.pathname, user?.role, rail, detailParent])

  /* the topbar gains a shadow only once there is something scrolled under it */
  useEffect(() => {
    const main = document.querySelector('.main')
    const onScroll = () => setScrolled(window.scrollY > 4)
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  useEffect(() => {
    const away = (e) => { if (!account.current?.contains(e.target)) setMenu(false) }
    document.addEventListener('mousedown', away)
    return () => document.removeEventListener('mousedown', away)
  }, [])

  useEffect(() => { setMenu(false); setOpen(false) }, [location.pathname])

  /*
   * Drop any entry whose feature this learner's track does not include. An entry lists
   * the features that would give it something to show; if none of them is on, the page
   * would be empty or refused, so it is not offered.
   */
  const groups = (NAV[user?.role] || [])
    .map(([section, links]) => [
      section,
      links.filter(([, , , feats, onlyTrack]) =>
        (!feats || feats.some((f) => can(f)))
        && (!onlyTrack || onlyTrack === trackType))
    ])
    .filter(([, links]) => links.length > 0)
  const [section, page] = crumbFor(location.pathname, user?.role)

  return (
    <div className={`shell ${rail ? 'railed' : ''}`}>
      {/* the first tab on any page jumps past the navigation */}
      <a className="skip" href="#main">Skip to content</a>
      <aside className={`sidebar ${open ? 'open' : ''}`}>
        <div className="sidebar-brand">
          {rail ? <RailMark /> : <Brand onDark />}
          <button
            className="rail-toggle"
            onClick={() => setRail((v) => !v)}
            aria-label={rail ? 'Expand the menu' : 'Collapse the menu'}
            title={rail ? 'Expand' : 'Collapse'}
          >
            <Icon name={rail ? 'expand' : 'collapse'} size={15} />
          </button>
        </div>

        <div className="nav-wrap" ref={navWrap}>
          <span
            className="nav-pill"
            style={{
              height: pill.height,
              transform: `translateY(${pill.top}px)`,
              opacity: pill.shown ? 1 : 0
            }}
          />
          {groups.map(([label, links]) => (
            <div key={label}>
              <div className="nav-section">{rail ? '' : label}</div>
              <nav className="nav flex-column">
                {links.map(([to, text, icon]) => (
                  <NavLink
                    key={to + text}
                    to={to}
                    end={['/learn', '/mentor', '/admin', '/super'].includes(to)}
                    className={({ isActive }) =>
                      `nav-link ${isActive || detailParent === to ? 'active' : ''}`}
                    title={rail ? text : undefined}
                  >
                    <Icon name={icon} size={17} />
                    <span className="nav-text">{text}</span>
                  </NavLink>
                ))}
              </nav>
            </div>
          ))}
        </div>

        {/* the account lives at the foot of the rail, where an account belongs */}
        <div className="account" ref={account}>
          {menu && (
            <div className="account-menu" role="menu">
              <button className="acct-item" onClick={toggle} role="menuitem">
                <Icon name={dark ? 'sun' : 'moon'} size={16} />
                {dark ? 'Light mode' : 'Dark mode'}
              </button>
              {user?.role === 'LEARNER' && (
                <button className="acct-item" onClick={() => navigate('/learn/profile')} role="menuitem">
                  <Icon name="profile" size={16} /> My profile
                </button>
              )}
              <div className="acct-sep" />
              <button
                className="acct-item danger"
                onClick={async () => {
                  await signOut()
                  /* back to the door they came in by */
                  navigate(user?.role === 'LEARNER' ? '/' : '/staff')
                }}
                role="menuitem"
              >
                <Icon name="logout" size={16} /> Sign out
              </button>
            </div>
          )}
          <button
            className={`account-btn ${menu ? 'on' : ''}`}
            onClick={() => setMenu((v) => !v)}
            aria-haspopup="menu"
            aria-expanded={menu}
          >
            <Avatar name={user?.name} size={30} onDark track={user?.trackType} />
            <span className="account-who">
              <strong>{user?.name}</strong>
              <span>{ROLE_LABEL[user?.role]}</span>
            </span>
            <Icon name="chevron" size={15} className="account-chev" />
          </button>
        </div>
      </aside>

      <div className="main">
        <header className={`topbar ${scrolled ? 'lifted' : ''}`}>
          {/* the bottom bar navigates on a phone, so this only exists for the
              tablet width where the sidebar is still the way around */}
          <button
            className="icon-btn drawer-toggle"
            onClick={() => setOpen((v) => !v)}
            aria-label="Open menu"
          >
            <Icon name="expand" size={16} />
          </button>

          <nav className="crumbs" aria-label="Breadcrumb">
            {section && <span className="crumb-section">{section}</span>}
            {page && <span className="crumb-page">{page}</span>}
          </nav>

          {user?.role === 'LEARNER' && (
            <div className="topbar-search d-none d-md-block">
              <SearchBox track={user?.trackType || 'BOTH'} />
            </div>
          )}


          <div className="topbar-right">
            {user?.role === 'LEARNER' && <CheckIn />}
          </div>
        </header>
        <main id="main">{children}</main>
      </div>
      <MobileNav />
    </div>
  )
}
