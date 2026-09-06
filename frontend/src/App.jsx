import { useState, lazy, Suspense } from 'react'
import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { useAuth } from './context/AuthContext'
import { LearnerProvider } from './context/LearnerContext'
import AppShell from './components/AppShell'
import BootSplash from './components/BootSplash'
import ViewSwap from './components/ViewSwap'
import { RoadmapSkeleton } from './components/Skeletons'

import CommandPalette from './components/CommandPalette'
import Offline from './components/Offline'
import useDocumentTitle from './hooks/useDocumentTitle'

/*
 * Every page is split out.
 *
 * The whole product was two chunks of about half a megabyte, so a learner on a phone
 * downloaded the super admin catalogue editor and the mentor desk before their own
 * roadmap could paint. A route now pulls only what it renders.
 */
const LearnerLogin = lazy(() => import('./pages/LearnerLogin'))
const StaffLogin = lazy(() => import('./pages/StaffLogin'))
const NotFound = lazy(() => import('./pages/NotFound'))
const ForgotPassword = lazy(() => import('./pages/ForgotPassword'))
const ResetPassword = lazy(() => import('./pages/ResetPassword'))
const ChangePassword = lazy(() => import('./pages/ChangePassword'))
const LearnerHome = lazy(() => import('./pages/learner/LearnerHome'))
const IntakeForm = lazy(() => import('./pages/learner/IntakeForm'))
const ChapterPlayer = lazy(() => import('./pages/learner/ChapterPlayer'))
const Sessions = lazy(() => import('./pages/learner/Sessions'))
const ProjectsAndTasks = lazy(() => import('./pages/learner/ProjectsAndTasks'))
const MockInterviews = lazy(() => import('./pages/learner/MockInterviews'))
const BatchSpace = lazy(() => import('./pages/learner/BatchSpace'))
const Resources = lazy(() => import('./pages/learner/Resources'))
const Recordings = lazy(() => import('./pages/learner/Recordings'))
const MyTime = lazy(() => import('./pages/learner/MyTime'))
const LearnerProfile = lazy(() => import('./pages/learner/LearnerProfile'))
const MentorHome = lazy(() => import('./pages/mentor/MentorHome'))
const AtRisk = lazy(() => import('./pages/mentor/AtRisk'))
const MyLearners = lazy(() => import('./pages/mentor/MyLearners'))
const MentorBatches = lazy(() => import('./pages/mentor/MentorBatches'))
const MentorTeam = lazy(() => import('./pages/mentor/MentorTeam'))
const Mentors = lazy(() => import('./pages/admin/Mentors'))
const MentorSlots = lazy(() => import('./pages/mentor/MentorSlots'))
const Tasks = lazy(() => import('./pages/mentor/Tasks'))
const MentorRecordings = lazy(() => import('./pages/mentor/Recordings'))
const Attendance = lazy(() => import('./pages/mentor/Attendance'))
const Schedules = lazy(() => import('./pages/mentor/Schedules'))
const LearnerRecord = lazy(() => import('./pages/mentor/LearnerRecord'))
const AdminHome = lazy(() => import('./pages/admin/AdminHome'))
const Register = lazy(() => import('./pages/admin/Register'))
const Batches = lazy(() => import('./pages/admin/Batches'))
const ImportSheet = lazy(() => import('./pages/admin/ImportSheet'))
const MailLog = lazy(() => import('./pages/admin/MailLog'))
const OnboardingBoard = lazy(() => import('./pages/admin/OnboardingBoard'))
const Cover = lazy(() => import('./pages/admin/Cover'))
const LiveBoard = lazy(() => import('./pages/admin/LiveBoard'))
const AdminProjects = lazy(() => import('./pages/admin/Projects'))
const Analytics = lazy(() => import('./pages/mentor/Analytics'))
const Features = lazy(() => import('./pages/superadmin/Features'))
const Faqs = lazy(() => import('./pages/superadmin/Faqs'))
const Modules = lazy(() => import('./pages/superadmin/Modules'))
const ModuleDetail = lazy(() => import('./pages/superadmin/ModuleDetail'))
const ChapterDetail = lazy(() => import('./pages/superadmin/ChapterDetail'))
const Courses = lazy(() => import('./pages/superadmin/Courses'))
const CourseDetail = lazy(() => import('./pages/superadmin/CourseDetail'))
const SuperHome = lazy(() => import('./pages/superadmin/SuperHome'))
const FormSections = lazy(() => import('./pages/superadmin/FormSections'))
const Settings = lazy(() => import('./pages/superadmin/Settings'))
const People = lazy(() => import('./pages/superadmin/People'))
const ActivityLog = lazy(() => import('./pages/superadmin/ActivityLog'))
const QuizDrafter = lazy(() => import('./pages/superadmin/QuizDrafter'))
const ProjectBuilder = lazy(() => import('./pages/superadmin/ProjectBuilder'))
const ProjectReview = lazy(() => import('./pages/mentor/ProjectReview'))
const ProjectWorkspace = lazy(() => import('./pages/learner/ProjectWorkspace'))





const HOME = {
  LEARNER: '/learn',
  MENTOR: '/mentor',
  ADMIN: '/admin',
  SUPER_ADMIN: '/super'
}

function Guard({ roles, children }) {
  const { user, ready } = useAuth()
  const location = useLocation()
  if (!ready) return <RoadmapSkeleton />
  if (!user) return <Navigate to="/" replace />
  if (user.mustChangePassword) return <Navigate to="/set-password" replace />
  if (roles && !roles.includes(user.role)) return <Navigate to={HOME[user.role]} replace />
  return (
    <AppShell>
      <ViewSwap swapKey={location.pathname}>{children}</ViewSwap>
    </AppShell>
  )
}

export default function App() {
  useDocumentTitle()
  const { user, ready } = useAuth()
  /* the intro plays once per browser session, not on every route change */
  const [booted, setBooted] = useState(() => sessionStorage.getItem('pib.booted') === '1')

  if (!booted) {
    return (
      <BootSplash onDone={() => {
        sessionStorage.setItem('pib.booted', '1')
        setBooted(true)
      }} />
    )
  }

  return (
    /*
     * One provider for the whole session, not one per route.
     *
     * It used to wrap each learner page individually, so every click unmounted it and
     * refetched the dashboard and continue-watching from scratch: two requests and a full
     * skeleton flash on every navigation. The data is the same on all of them. Mounted
     * once here it is fetched once, and moving between pages is instant.
     *
     * It costs nothing for staff: the loader returns immediately for any role that is not
     * a learner.
     */
    <LearnerProvider>
      <CommandPalette />
      <Offline />
      <Suspense fallback={<BootSplash />}>
      <Routes>
      <Route
        path="/"
        element={
          !ready ? <RoadmapSkeleton />
            : user ? <Navigate to={user.mustChangePassword ? '/set-password' : HOME[user.role]} replace />
              : <LearnerLogin />
        }
      />
      <Route
        path="/staff"
        element={
          !ready ? <RoadmapSkeleton />
            : user ? <Navigate to={user.mustChangePassword ? '/set-password' : HOME[user.role]} replace />
              : <StaffLogin />
        }
      />
      <Route path="/forgot" element={<ForgotPassword />} />
      <Route path="/reset" element={<ResetPassword />} />
      <Route path="/set-password" element={<ChangePassword />} />

      <Route path="/learn" element={<Guard roles={['LEARNER']}><LearnerHome /></Guard>} />
      <Route path="/learn/intake" element={<Guard roles={['LEARNER']}><IntakeForm /></Guard>} />
      <Route path="/learn/chapter/:id" element={<Guard roles={['LEARNER']}><ChapterPlayer /></Guard>} />
      <Route path="/learn/sessions" element={<Guard roles={['LEARNER']}><Sessions /></Guard>} />
      <Route path="/learn/projects" element={<Guard roles={['LEARNER']}><ProjectsAndTasks /></Guard>} />
      {/* the guided project workspace; /learn/projects stays as tasks and declared work */}
      <Route path="/learn/project-work" element={<Guard roles={['LEARNER']}><ProjectWorkspace /></Guard>} />
      <Route path="/learn/mock" element={<Guard roles={['LEARNER']}><MockInterviews /></Guard>} />
      <Route path="/learn/batch" element={<Guard roles={['LEARNER']}><BatchSpace /></Guard>} />
      <Route path="/learn/resources" element={<Guard roles={['LEARNER']}><Resources /></Guard>} />
      <Route path="/learn/recordings" element={<Guard roles={['LEARNER']}><Recordings /></Guard>} />
      <Route path="/learn/profile" element={<Guard roles={['LEARNER']}><LearnerProfile /></Guard>} />
      <Route path="/learn/time" element={<Guard roles={['LEARNER']}><MyTime /></Guard>} />

      <Route path="/mentor" element={<Guard roles={['MENTOR', 'ADMIN', 'SUPER_ADMIN']}><MentorHome /></Guard>} />
      <Route path="/mentor/at-risk" element={<Guard roles={['MENTOR', 'ADMIN', 'SUPER_ADMIN']}><AtRisk /></Guard>} />
      <Route path="/mentor/learners" element={<Guard roles={['MENTOR', 'ADMIN', 'SUPER_ADMIN']}><MyLearners /></Guard>} />
      <Route path="/mentor/learners/:id" element={<Guard roles={['MENTOR', 'ADMIN', 'SUPER_ADMIN']}><LearnerRecord /></Guard>} />
      <Route path="/mentor/projects" element={<Guard roles={['MENTOR', 'ADMIN', 'SUPER_ADMIN']}><ProjectReview /></Guard>} />
      <Route path="/mentor/slots" element={<Guard roles={['MENTOR', 'ADMIN', 'SUPER_ADMIN']}><MentorSlots /></Guard>} />
      <Route path="/mentor/tasks" element={<Guard roles={['MENTOR', 'ADMIN', 'SUPER_ADMIN']}><Tasks /></Guard>} />
      <Route path="/mentor/recordings" element={<Guard roles={['MENTOR', 'ADMIN', 'SUPER_ADMIN']}><MentorRecordings /></Guard>} />
      <Route path="/mentor/attendance" element={<Guard roles={['MENTOR', 'ADMIN', 'SUPER_ADMIN']}><Attendance /></Guard>} />
      <Route path="/mentor/batches" element={<Guard roles={['MENTOR', 'ADMIN', 'SUPER_ADMIN']}><MentorBatches /></Guard>} />
      <Route path="/mentor/team" element={<Guard roles={['MENTOR', 'ADMIN', 'SUPER_ADMIN']}><MentorTeam /></Guard>} />

      <Route path="/admin" element={<Guard roles={['ADMIN', 'SUPER_ADMIN']}><AdminHome /></Guard>} />
      <Route path="/admin/register" element={<Guard roles={['ADMIN', 'SUPER_ADMIN']}><Register /></Guard>} />
      <Route path="/admin/batches" element={<Guard roles={['ADMIN', 'SUPER_ADMIN']}><Batches /></Guard>} />
      <Route path="/admin/import" element={<Guard roles={['ADMIN', 'SUPER_ADMIN']}><ImportSheet /></Guard>} />
      <Route path="/admin/mail" element={<Guard roles={['ADMIN', 'SUPER_ADMIN']}><MailLog /></Guard>} />
      <Route path="/admin/onboarding" element={<Guard roles={['ADMIN', 'SUPER_ADMIN']}><OnboardingBoard /></Guard>} />
      <Route path="/admin/cover" element={<Guard roles={['ADMIN', 'SUPER_ADMIN']}><Cover /></Guard>} />
      <Route path="/admin/mentors" element={<Guard roles={['ADMIN', 'SUPER_ADMIN']}><Mentors /></Guard>} />
      {/* one week, one screen. The server decides what each role may change on it. */}
      {/* one week, one route. /admin/board and /mentor/week both rendered this same
          screen under two different names, which is how the office and the mentors ended
          up describing the same thing differently in conversation. */}
      <Route path="/admin/board" element={<Navigate to="/week" replace />} />
      <Route path="/mentor/schedules" element={<Navigate to="/week/repeating" replace />} />
      <Route path="/mentor/week" element={<Navigate to="/week" replace />} />
      <Route
        path="/week"
        element={<Guard roles={['MENTOR', 'ADMIN', 'SUPER_ADMIN']}><LiveBoard /></Guard>}
      />
      <Route
        path="/week/repeating"
        element={<Guard roles={['MENTOR', 'ADMIN', 'SUPER_ADMIN']}><Schedules /></Guard>}
      />
      <Route
        path="/analytics"
        element={<Guard roles={['MENTOR', 'ADMIN', 'SUPER_ADMIN']}><Analytics /></Guard>}
      />
      <Route path="/admin/projects" element={<Guard roles={['ADMIN', 'SUPER_ADMIN']}><AdminProjects /></Guard>} />
      <Route path="/super" element={<Guard roles={['SUPER_ADMIN']}><SuperHome /></Guard>} />
      <Route path="/super/modules" element={<Guard roles={['SUPER_ADMIN']}><Modules /></Guard>} />
      <Route path="/super/modules/:id" element={<Guard roles={['SUPER_ADMIN']}><ModuleDetail /></Guard>} />
      {/* a mentor opens this to set and mark the work; the page shows them the
          assignment only, and the server enforces the same split */}
      <Route
        path="/super/chapters/:id"
        element={<Guard roles={['MENTOR', 'ADMIN', 'SUPER_ADMIN']}><ChapterDetail /></Guard>}
      />
      <Route path="/super/courses" element={<Guard roles={['SUPER_ADMIN']}><Courses /></Guard>} />
      <Route path="/super/courses/:id" element={<Guard roles={['SUPER_ADMIN']}><CourseDetail /></Guard>} />
      {/* an admin decides what a track includes, so the toggles are theirs too */}
      <Route path="/super/features" element={<Guard roles={['ADMIN', 'SUPER_ADMIN']}><Features /></Guard>} />
      <Route path="/super/faqs" element={<Guard roles={['SUPER_ADMIN']}><Faqs /></Guard>} />
      <Route path="/super/settings" element={<Guard roles={['SUPER_ADMIN']}><Settings /></Guard>} />
      <Route path="/super/form" element={<Guard roles={['SUPER_ADMIN']}><FormSections /></Guard>} />
      <Route path="/super/people" element={<Guard roles={['SUPER_ADMIN']}><People /></Guard>} />
      <Route path="/super/activity" element={<Guard roles={['SUPER_ADMIN']}><ActivityLog /></Guard>} />
      <Route path="/super/quiz-drafter" element={<Guard roles={['SUPER_ADMIN']}><QuizDrafter /></Guard>} />
      <Route path="/super/projects" element={<Guard roles={['SUPER_ADMIN']}><ProjectBuilder /></Guard>} />

      <Route path="*" element={<NotFound />} />
      </Routes>
      </Suspense>
    </LearnerProvider>
  )
}
