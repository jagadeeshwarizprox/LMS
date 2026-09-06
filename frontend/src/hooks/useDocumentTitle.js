import { useEffect } from 'react'
import { useLocation } from 'react-router-dom'

/**
 * A title per screen.
 *
 * Ten open tabs all reading "ProITBridge" is ten tabs nobody can tell apart, and staff
 * genuinely do keep a learner, the register and the builder open at once.
 */
const TITLES = [
  [/^\/learn\/chapter/, 'Subtopic'],
  [/^\/learn\/recordings/, 'Recorded sessions'],
  [/^\/learn\/sessions/, 'Sessions'],
  [/^\/learn\/project-work/, 'My project'],
  [/^\/learn\/projects/, 'Tasks'],
  [/^\/learn\/batch/, 'My batch'],
  [/^\/learn\/time/, 'My study time'],
  [/^\/learn\/profile/, 'My profile'],
  [/^\/learn/, 'My roadmap'],
  [/^\/mentor\/learners\//, 'Learner'],
  [/^\/mentor\/learners/, 'My learners'],
  [/^\/mentor\/tasks/, 'Tasks'],
  [/^\/mentor\/at-risk/, 'At risk'],
  [/^\/mentor\/attendance/, 'Attendance'],
  [/^\/mentor\/recordings/, 'Sessions to publish'],
  [/^\/analytics/, 'Analytics'],
  [/^\/week\/repeating/, 'Repeating sessions'],
  [/^\/week/, 'Weekly sessions'],
  [/^\/mentor/, 'My desk'],
  [/^\/admin\/onboarding/, 'Onboarding board'],
  [/^\/admin\/register/, 'All learners'],
  [/^\/admin\/cover/, 'Cover'],
  [/^\/admin\/coverage/, 'Recording coverage'],
  [/^\/admin/, 'Admin'],
  [/^\/super\/settings/, 'Settings'],
  [/^\/super\/faqs/, 'Questions and answers'],
  [/^\/super/, 'Course builder'],
  [/^\/staff/, 'Team sign in'],
  [/^\/forgot/, 'Forgot password'],
  [/^\/reset/, 'Set a new password']
]

export default function useDocumentTitle() {
  const { pathname } = useLocation()
  useEffect(() => {
    const hit = TITLES.find(([re]) => re.test(pathname))
    document.title = hit ? `${hit[1]} · ProITBridge` : 'ProITBridge'
  }, [pathname])
}
