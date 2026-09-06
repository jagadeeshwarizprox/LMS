import { ArrowUp, ArrowDown,
  LayoutDashboard, Map, CalendarDays, FolderKanban, MessageSquareQuote, Users,
  PlayCircle, Briefcase, ClipboardList, UserRound, Clock, Inbox, TriangleAlert,
  CalendarClock, Settings2, Library, ToggleRight, HelpCircle, Network, ScrollText,
  ShieldCheck, Upload, Mail, Boxes, ListChecks, Moon, Sun, LogOut, ChevronDown,
  PanelLeftClose, PanelLeftOpen, Search, X, Check, RotateCw, Ban, Pencil, Loader2,
  Menu, Columns3
} from 'lucide-react'

/**
 * One icon set, one weight, one size scale. Everything is referenced by name so a
 * swap is a single map, and no screen imports the library directly.
 */
const MAP = {
  dashboard: LayoutDashboard, roadmap: Map, sessions: CalendarDays, projects: FolderKanban,
  mock: MessageSquareQuote, batch: Users, recordings: PlayCircle, resources: Briefcase,
  intake: ClipboardList, profile: UserRound, time: Clock, queues: Inbox,
  risk: TriangleAlert, slots: CalendarClock, catalogue: Settings2, library: Library,
  features: ToggleRight, faqs: HelpCircle, people: Network, activity: ScrollText,
  security: ShieldCheck, import: Upload, mail: Mail, batches: Boxes,
  onboarding: ListChecks, moon: Moon, sun: Sun, logout: LogOut, chevron: ChevronDown,
  collapse: PanelLeftClose, expand: PanelLeftOpen, search: Search, close: X,
  check: Check, rotate: RotateCw, ban: Ban, edit: Pencil, spinner: Loader2,
  menu: Menu, signout: LogOut, board: Columns3, settings: Settings2,
  shield: ShieldCheck, layers: Boxes, book: Library, form: ClipboardList,
  up: ArrowUp, down: ArrowDown
}

export default function Icon({ name, size = 17, className = '', strokeWidth = 1.9 }) {
  const C = MAP[name]
  /* a missing name used to render nothing at all, which is a blank button nobody
     can identify; in development it should be loud */
  if (!C) {
    if (import.meta.env.DEV) console.warn(`Icon "${name}" is not in the map.`)
    return null
  }
  return <C size={size} strokeWidth={strokeWidth} className={className} aria-hidden="true" />
}
