# ProITBridge LMS

Full stack implementation of the LMS design specification.

- **Frontend** React 18, Context API for state, Bootstrap 5, CSS3, Vite
- **Backend** Spring Boot 3.3 (Java 17), Spring Security with JWT
- **Database** MongoDB

Colours are sampled from the PROITBRIDGE logo: navy `#01153A` / `#002060`, bright blue
`#00B0F0`, teal `#008A9D`.

**Running it for the first time? Read `EXECUTION.md`.** It is one path from a fresh
machine to a working course with learners on it. This file explains why things are the way
they are; that one tells you what to type.

---

## Run it

### 1. MongoDB

```bash
docker run -d --name pib-mongo -p 27017:27017 mongo:7
```

Or point `MONGODB_URI` at any existing cluster.

### 2. Backend

```bash
cd backend
mvn spring-boot:run          # http://localhost:8080
```

On an empty database this creates one super admin from `BOOTSTRAP_EMAIL` and
`BOOTSTRAP_PASSWORD`, the feature toggles the code branches on, and a first draft of the
information form. There is no demo content. See **A fresh database is empty**.

### 3. Frontend

```bash
cd frontend
npm install
npm run dev                  # http://localhost:5173
```

Vite proxies `/api` to `localhost:8080`, so no CORS work is needed in development.

### Demo logins

The database has no accounts until you create the first one:

```bash
BOOTSTRAP_EMAIL=you@proitbridge.com
BOOTSTRAP_PASSWORD=something-long-and-temporary
```

That is the only account that exists. It signs in at `/staff`, is made to change its
password immediately, and creates everyone else from inside the product.


---

## Environment

| Variable | Default | Notes |
|---|---|---|
| `MONGODB_URI` | `mongodb://localhost:27017/pib_lms` | |
| `JWT_SECRET` | dev value | Change before deploying |
| `JWT_TTL_MINUTES` | `720` | |
| `MAIL_ENABLED` | `false` | `false` writes to `mail_log` only |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USER` / `SMTP_PASSWORD` | | Needed only when mail is on |
| `BOOTSTRAP_EMAIL` / `BOOTSTRAP_PASSWORD` / `BOOTSTRAP_NAME` | | The first super admin, only on an empty database |
| `LMS_BASE_URL` | `http://localhost:5173` | Used in credential mails |
| `LMS_WALKTHROUGH_URL` | placeholder | Link in the credential mail |
| `CORS_ORIGINS` | `http://localhost:5173` | Comma separated |
| `VIDEO_PROVIDER` | `YOUTUBE` | or `CLOUDFLARE_STREAM` |
| `CF_CUSTOMER_CODE` / `CF_STREAM_KEY_ID` / `CF_STREAM_PRIVATE_KEY` | | Only for Cloudflare Stream |
| `DEVICE_LIMIT` | `2` | Registered devices per account |
| `IDLE_MINUTES` | `120` | Session idle timeout |
| `OLLAMA_URL` | `http://localhost:11434` | |
| `OLLAMA_MODEL` | `llama3.1` | |
| `AI_ENABLED` | `true` | `false` falls back to rules everywhere |

Credential mails are always written to the `mail_log` collection and are visible at
**Admin → Credential mail log**, so the whole provisioning flow can be tested without a
mail server.

---

## How the spec became code

**Provisioning.** There is no self sign up. `ImportService` reads
`ProITbridge_Student_Records.xlsx`; sheet membership decides the track, since a row in
the premium sheet is a premium learner and a row in the group sheet is a batch learner.
A row exists only after the sale closes, so a new row is treated as payment confirmed.
The importer is the only class that knows where records come from, so swapping the sheet
for a CRM feed later touches one adapter and nothing else. Manual enrolment at
**Admin → All learners → Enrol one learner** runs through the same `ProvisioningService`,
so the two paths cannot drift apart.

**Duplicates and upgrades.** Email is the unique key. A batch learner who appears in the
premium sheet is upgraded in place, keeping progress and history, rather than getting a
second account.

**Credentials.** Every account gets a login ID made from the person's name and a first
password made the same way, mailed with the walkthrough link. `mustChangePassword` blocks
every route until it is replaced. See **Signing in** below for why it is derived rather
than generated, and the three things that keep that safe.

**Onboarding gates.** Three gates, drawn in the UI as one connected rail: information
form, prerequisite video, and the onboarding call (premium) or Tuesday induction (batch).

Whether they block anything is a setting, **Require onboarding before the course opens**,
and it currently ships **off**. Off, the three still show on the learner's home as a
checklist worth doing, and the course opens on first sign in.

Two methods rather than one, because these are different questions.
`Learner.gatesCleared()` is the honest state of the three flags and is what the onboarding
board and the mentor views read, so switching enforcement off does not empty the list of
people who still need a call. `Learner.modulesUnlocked()` is whether the course is open,
and it is what all sixteen enforcement points check, including the video grant.

The flag lives in `GatePolicy`, a static holder refreshed when the setting is written and
once at startup. Static mutable state is worth being uneasy about; it earns its place
because a Mongo document cannot be given a service, there is exactly one value, and the
alternative is sixteen call sites each remembering to ask.

**Migrations.** Two, both one time and both guarded by a setting so a restart cannot
re-run them. `CatalogueMigration` splits the old two levels into three.
`LoginIdMigration` backfills login IDs without touching a password.

Every old chapter id stays a chapter id, which is what makes the move
cheap: quiz questions, attempts, assignments, mock requests and progress rows are keyed
on `chapterId` and none are touched. What moves is the video and the material, into one
new topic per old chapter. Anyone who had finished the old chapter is credited with
having watched its only video. It runs once, guarded by a setting, in `CatalogueMigration`.

**Two names worth knowing.** `java.lang.Module` has existed since Java 9, so a domain
class called `Module` makes every wildcard import ambiguous and nothing compiles. The
class is `CourseModule` in Java while the collection, the JSON and the screens all say
module. Likewise the chapter's assignment brief is `Chapter.AssignmentSpec`, because
`Assignment` is already the document a learner hands in.

**The information form.** Eight sections, each saving independently. Section three
branches three ways on fresher, working professional or career gap. A career gap is
surfaced on the mentor's Background tab with a note, since it changes interview
preparation and not just the roadmap.

**Three levels.** Module, chapter, topic. A module is a subject. A chapter is a sitting:
the group of topics that get tested and assigned together. A topic is one video and the
files that go with it.

The middle level is the one that had to be added. A chapter used to carry the video and
the assessment on one object, so every video came with its own test and its own task,
which is not how the material is taught. Four short videos on control flow are one thing
to be tested on, not four.

**Chapter flow.** Work through the topics, then test, teach back, assignment. The first
test attempt is the recorded score and later attempts are practice. A chapter counts as
complete only when every topic in it has been watched and the three switched-on steps are
done, and an empty chapter is never complete, because that is a content gap rather than a
free pass. Chapters open in order, and topics inside a chapter open in order against each
other; both are enforced server side, including in the video grant.

**Batch model.** Fixed Tuesday starts; a mid-batch joiner whose induction has already
been held watches the recording rather than triggering a second live session. Doubt
clearing is a fixed group session with no one to one booking, which the backend
enforces. The batch space is read only, with conversation kept on WhatsApp.

**At risk.** Biweekly calls do not scale to seventy learners, so drift is computed from
activity already in the LMS: no sign in for seven days, an overdue task, or onboarding
still open a week after joining. Cohort pace gives the batch learner the same signal
about themselves.

**Mock interviews.** Premium gets one automatically when a module completes; approving
the last task in a module opens it. Batch needs one approved project first and then
requests one. The tile stays visible with the reason shown rather than hidden.

**Admin vs Super Admin.** Admin runs the machine: enrolment, mentor assignment
(round robin with manual override), batches, credential resends, per learner feature
exceptions. Super Admin changes what the machine is: catalogue, pricing, roles and
reporting hierarchy, per track feature toggles, and deletion. Reporting is transitive,
so a lead sees everything under their whole tree.

**Feature toggles.** What differs between premium and batch is configuration, not code.
Fourteen toggles ship seeded; **Super Admin → Track features** edits them and an admin
can override one for a single learner.

---

## The motion layer

Front end only. No route, payload or rule changed when this went in.

**Boot.** `BootSplash.jsx` plays once per browser session, tracked in `sessionStorage`.
The node graph draws itself edge by edge, dissolves into the wordmark, and a rail fills
through four named stages while the app comes up. It gates nothing: if data lands early
the rail finishes early.

**Logo.** Two cuts of the same file live in `public/`. The white plate is keyed out of
both. `logo-on-dark.png` lifts the wordmark but leaves the globe tile at `#01153A`, the
exact navy of the sidebar and splash, so the tile dissolves into the surface instead of
sitting on it as a white box. `Brand.jsx` picks the cut by surface.

**One entrance per view.** `ViewSwap.jsx` pre-hides incoming content, paints it, then
reveals it on the next frame with a single 260ms fade and rise. Deliberately not a per
card stagger: that reads as the app rebuilding itself on every click. Verified headless
across every route in both themes with zero frames visible while empty.

**Motion that carries meaning.** Progress bars ease from their previous value, remembered
per bar id, never from zero on a re-render (`Bar` in `Ui.jsx`). The gate rail's teal fill
travels along the connector only when a gate is newly cleared, and is set instantly on
every later paint (`GateRail.jsx`). Step pips pop the moment they are earned (`Pip`). The
sidebar has one pill that slides between items, measured after layout (`AppShell.jsx`).
Tab underlines slide, tracking both axes so they stay correct when tabs wrap
(`TabBar.jsx`).

**Rest.** Shaped skeletons per surface in `Skeletons.jsx`, so content swaps into the same
footprint. Dark mode in `ThemeContext.jsx`, persisted, with the sidebar and splash staying
navy in both. Toasts slide and settle. Card hover lift is 1px. `prefers-reduced-motion`
respected throughout.

Fixed along the way, pre-existing and unrelated to the visuals: Sessions, Mock interviews,
Information form and My profile all read the learner record inside an effect that could
run before the dashboard payload arrived, throwing on `trackType` and `name`. They now
wait for the payload.

---

## Layout

```
backend/
  pom.xml
  src/main/resources/application.yml
  src/main/java/com/proitbridge/lms/
    domain/      53 MongoDB documents, CourseModule > Chapter > Topic > Resource
    repo/        53 Spring Data repositories
    security/    JWT filter with session validation, config, current user
    service/     provisioning, credentials, import, catalogue, catalogue migration,
                 login id migration, learner, mentor, admin, super admin, mail,
                 session and devices, video access, anomaly sweep, meetings, faq, ollama
    service/video/  VideoProvider, YouTubeProvider, CloudflareStreamProvider
    web/         REST controllers, error handler
    config/      BootstrapSeeder
frontend/
  public/        logo-ink.png (light surfaces), logo-on-dark.png (navy surfaces)
  src/api/       fetch wrapper
  src/context/   AuthContext, LearnerContext, ToastContext, ThemeContext
  src/components/AppShell, BootSplash, Brand, GateRail, SecurePlayer, DoubtBlock,
                 FaqBlock, SearchBox, Skeletons, TabBar, ViewSwap, Ui
  src/pages/     learner, mentor, admin, superadmin
                 superadmin/ Modules, ModuleDetail, ChapterDetail, Courses, CourseDetail
  src/styles/theme.css   design tokens, dark tokens, and the motion rules
```

---

## Content security

The requirement was that a leaked link is useless to an outsider. Here is exactly what
holds and what does not.

**Where ids live.** A provider identifier exists only on a `Video` document. Nothing else
in the API returns one: not the roadmap, not the chapter payload, not the recordings
list, not the admin library, where it is masked because running the LMS never needs to
see it. Pressing play calls `POST /api/video/{ref}/grant`; the server re-derives
entitlement from scratch (enrolment, track, gates, chapter order, batch for a recording),
hands over one id for one play, and writes a `VideoAccessLog` row with the account,
session, device and IP.

**What that buys.** No enumeration, no id in the bundle, no id for a chapter the learner
has not unlocked, and every playback attributable. What it does not buy: the iframe must
carry the id for a YouTube embed to work at all, so devtools can read it. That is the
embed, not the implementation.

**So a leak is fixable rather than permanent.** An admin rotates a video: re-upload as a
new unlisted video, paste the new id, and every chapter, recording and session pointing
at it follows while the leaked one is orphaned. The player carries the learner's name and
email as a wandering watermark, so a screen recording identifies its own source and
rotate plus suspend takes ten minutes.

**If that is not enough**, `CloudflareStreamProvider` is written and one config line away
(`VIDEO_PROVIDER=CLOUDFLARE_STREAM`). Signed tokens expire in two minutes and refuse to
play off your domain, which is the only way a forwarded link is genuinely dead. Nothing
else in the application changes when you switch.

**Account sharing** is handled separately, because it is the real leak in every course
platform. One live session per account, enforced on every request rather than at login,
so signing in anywhere drops the previous session. Two registered devices, third refused
until an admin releases a slot. One concurrent playback. Suspend drops everything at
once. A nightly sweep raises flags for several networks in a day, grant bursts beyond
what one person watches, device churn, and sign-ins pushing each other out. Nothing acts
automatically; every flag needs a person, on **Admin → Access and sharing**.

---

## The model layer

Ollama, behind `OllamaService`, so the model is one setting and every call is stored with
the model name that produced it.

- **Teach back** is graded against the chapter and returns a score with one thing to fix.
  If Ollama is unreachable the length rubric takes over, because a learner should never
  be stuck mid chapter waiting on a server.
- **Doubt block** answers about the current chapter only and says so when a question is
  wider, pointing at the doubt clearing session. Every exchange is kept, so mentors can
  see where a cohort is actually stuck.
- **Quiz drafting** writes candidate questions for a chapter. They are stored as drafts,
  are never served to a learner, and a person edits and publishes them.
- **FAQ fallback** answers only when nothing curated matches, and is labelled.

Config: `OLLAMA_URL`, `OLLAMA_MODEL`, `OLLAMA_TIMEOUT`, `AI_ENABLED`.

---

## Why a join fails

Four things have to line up before a learner can join, and each can be individually fine
while they still cannot get in. The two that actually bite:

**The room was resolved too early.** A meeting room belongs to a mentor, one each. A slot
gets a `roomId` copied onto it when its schedule is saved, so a link set *afterwards*
never reaches a slot that already existed. The copy is stale forever and join answers that
no link is set, which reads as a bug in the product rather than an ordering problem.

`roomForSlot` now resolves at read time: the slot's own room wins when it has one with a
link on it, otherwise it falls back to the mentor's current room. No migration, and a
session deliberately pointed at another room keeps working.

**The window.** Learners are refused outside it, opening fifteen minutes before the start
and closing fifteen after the end; staff bypass it entirely. Testing as a learner against
a session scheduled for tomorrow is correctly refused, and the old message said only that
it opens fifteen minutes before, which on a session two days out reads like a broken link.
It now says which case it is and when the session opens.

The other two are scope rules rather than faults: a premium learner needs to have booked
or the session's track scope has to match theirs, and a batch-scoped session does not open
for somebody outside that batch.

**Mentor, Slots and sessions, Check** walks every upcoming session and reports what would
happen if somebody pressed join, so this is readable before a call rather than during one.

## Sessions, recordings and meetings

A mentor has one standing meeting room. Slots point at the room rather than carrying
their own URL, so a link is stored once and is never rendered into a schedule page. The
join link is fetched by `POST /api/slots/{id}/join` at click time, open from ten minutes
before the start until fifteen minutes after the end, and only for a learner who is
booked or whose track the session covers.

A mentor publishes a recording by pasting the unlisted id once. It becomes a `Video` and
everything downstream refers to it. Recordings play through the same grant flow as
chapters, with no exceptions, and are filtered to the batch and track they belong to.

---

## Mentors

The relationship is the product, so a mentor is assigned once and then left alone. Round
robin only fills an empty seat: import, upgrade and batch moves never reshuffle anyone.
Moving a learner needs an explicit mentor and a written reason, both kept in
`mentor_assignments` and shown on the learner's record. The admin register shows the
mentor as text with a Change action, not a dropdown that reassigns on a stray click.

---

## Questions and answers

Thirty seeded from the questions learners actually ask, each with a placement so it
appears where the question arises: the sign in screen, the onboarding card, the chapter
player, sessions, mock interviews, the batch space and the profile. A search box in the
top bar covers both questions and chapter titles. Super admin edits them under
**Questions and answers**.

---

## Check in and study time

A learner presses check in when they sit down. The clock only advances on heartbeats
sent while the tab is visible and the learner has moved or typed since the last beat,
so a tab left open overnight earns nothing. A forgotten check out is closed at the last
heartbeat, never at the time the sweep happens to run. That is the whole reason the
number is worth showing.

They see today, this week, all time, a day streak, a two week bar chart and a table of
recent days. A mentor sees the same effort on the learner record, with this week against
last week, which is the earliest sign someone is drifting: minutes fall before tasks do.

## Moving a learner

One panel on the learner record covers every move: batch to premium, premium to batch,
one batch to another, changing course, and putting someone on hold.

The rule underneath all of it is that a move never deletes anything. Progress, tasks,
tests, projects, resumes and mentor history survive every one, because a learner who
changes batch has not stopped being the same person. Changing course keeps progress on
modules the two courses share and keeps the rest, so moving back restores everything. An
upgrade from batch to premium is not re-gated: someone who has been studying for a month
does not sit through onboarding again. Induction only re-applies when the learner lands
in a batch that has not held its own yet.

Every move needs a reason, and every reason is kept in `learner_moves` with who did it
and when.

**On hold** is its own state: no nudges, no at risk flags, modules frozen, and the
at-risk sweep skips them entirely. Life happens, and chasing someone who has already
told you they are dealing with something is worse than silence.

## The Wednesday rule

A new batch starts every Tuesday. Someone who joins on a Wednesday goes into the batch
that has just started rather than waiting six days for the next one. This now runs
automatically on import and on manual enrolment when no batch code is given, and the
move panel shows which batch the rule suggests. An admin can always override it.

## Onboarding board

The lifecycle has five stages but the LMS only ever showed the learner their own three
gates, so nobody on the admin side could see who was stuck where. **Admin → Onboarding
board** shows every learner still in onboarding, their position across seven steps
(account, credentials, first sign in, group link, form, prerequisite, call or induction),
which step they are stuck on, and how long they have been there. Anything past a week is
flagged, because at that point somebody should call rather than email again.

## This week

One card at the top of the roadmap: the next chapter, anything the mentor sent back, the
next session, a scheduled call or mock. Everything on it is derived from the same data as
the rest of the LMS, so it can never disagree with it. Self paced learners drift because
nothing tells them where to start, and a forty chapter roadmap is not an answer.

## Chapter notes

A learner's own notes against a chapter, saved a moment after they stop typing. Theirs
alone; no mentor or admin surface reads them.

---

## The mentor desk

Mentors had queues and a flat list, which is not how anyone actually works.

**My learners** is now grouped by cohort, with premium as its own group, because a
mentor holds their learners as cohorts rather than as a list of thirty names. Every row
carries the two facts that decide who to contact today: when they last did anything, and
what is waiting on whom. Filters for waiting on me, at risk, quiet this week and still
onboarding; sorting by progress, last seen, waiting or study time; and a row opens a
drawer rather than a page, so the list keeps its place. A progress distribution across
the whole roster sits above it.

**My desk** replaces the bare queue list. Today's sessions and the calls due this week
sit in one band, with the oldest waiting reviews beside them and their age in days,
because a queue four days old is a different problem from a busy one.

**Where the cohort is stuck** groups every doubt the mentor's learners asked the
assistant, by chapter, over the last two weeks. A mentor walks into a session already
knowing the top five questions, which is worth more than any dashboard number. The data
was being logged from the day the doubt block shipped and nobody could see it.

**Effort** appears on the learner record and in the drawer: this week against last, days
active out of the last fourteen, and a two week bar chart. Minutes fall before tasks do,
which makes it the earliest signal a mentor gets.

## Charts

Drawn in SVG, no library: `Ring` for progress, `Bars` for a run of days, `BandBar` for a
distribution, `Trend` for two numbers and a direction. They share one palette and one
idea of an empty state, and the trend arrow hides itself when there is nothing to compare
against, because an arrow against an identical number says nothing.

---

## Tasks a mentor can actually set

A mentor could only review work a chapter had already generated. Now they can set a task
for one learner, a selection, or a whole batch, with a brief, a due date and a rubric.

**Due dates finally do something.** `dueAt` existed from the first build and nothing ever
set it, so the at-risk sweep had been counting overdue tasks that could never become
overdue. Mentor → Tasks lists everything late across their learners.

**Nothing is overwritten.** Every submission and every review is an event on a thread, so
what was asked and what came back survives a resubmission. That conversation is most of
the teaching and it used to vanish.

**Rubrics** are named criteria with weights. The overall score is derived from them, so
two mentors scoring the same work land in the same place. Two ship seeded, one for tasks
and one for projects.

**Bulk review** approves several at once with one comment, because four approvals should
not be four screens.

## File upload

Submissions and resumes were links to somebody's Drive, which breaks within a month.
`FileService` stores bytes under a random key, never the filename, checks type and size
on the way in, and serves every download through an authorised read. A traversal in a
filename cannot escape the directory because the filename is never used as a path.
Configured with `FILES_DIR` and `FILES_MAX_BYTES`.

## A schedule that keeps itself

`SessionSchedule` says which day and time a session runs. Slots are generated two weeks
ahead, refreshed nightly, and the generator is idempotent. Sessions can be cancelled with
a reason or rescheduled, and everyone booked is told. Attendance is a sheet per session,
and notes go on the session so every attendee's record carries what was covered.

Four schedules ship seeded: Monday and Tuesday doubt clearing, the Saturday project
session, and a Wednesday live session.

## Nudges that leave a trace

At risk named a problem and offered a WhatsApp link, so the follow up left no record. A
nudge is now drafted against the actual reason, editable before sending, and kept on the
learner's record with everything else. `MessageService` also handles mentor to learner
notes and cohort broadcasts, all of which land in mail and on the record.

---

## On a phone

A 74px rail and a slide-out sidebar are desktop patterns. On a phone the thumb reaches
the bottom of the screen and very little else, which is why the sidebar disappears
entirely below 860px and a **bottom bar** takes over: four destinations per role plus
More, which opens a sheet rather than another screen. Four, because five is where a bar
starts to feel like a menu. Every target is at least 52px.

Wide tables **become cards**, with each column header read out as a label beside its
value. A horizontal scroll on a phone is a scroll nobody discovers. Drawers become
sheets that come up from the bottom, because a 560px panel on a 390px screen is a panel
off screen. The attendance buttons go full width in a row of three, since that is the
screen a mentor actually uses after a session.

## Keyboard and screen readers

The first tab on any page is **skip to content**, so nobody drives through the whole
sidebar to reach the thing they came for. Focus rings use `:focus-visible`, so keyboard
users see them and mouse users never do. Every icon-only button has a name; a button with
only an icon in it announces as "button" and tells the person nothing.

`prefers-reduced-motion` is honoured, which it was not before: every animation played
regardless of what the person had asked their machine for.

## Cmd-K

Everything is one search away. A mentor with forty learners should not reach one by
remembering which cohort they are in and scrolling; typing three letters is how every
tool they already use works.

Learners and topics load once when the palette first opens, not per keystroke: the
sets are small, and a search that waits on the network stops feeling like search. A
word-start match ranks above one buried in the middle, which is what makes two letters
feel like it read your mind. Arrows and Enter work, and the mouse moves the same cursor
rather than fighting it. The shortcut renders as cmd or ctrl depending on the keyboard.

## Undo instead of confirm

A confirmation dialog asks every time, including the ninety-nine times the person was
right, which trains them to click through without reading. Undo costs nothing when you
meant it and saves you when you did not.

Reviewing a task paints the result immediately and sends the request ten seconds later,
so the common case is one call rather than two, and the turn is marked "sending, undo
while you can" until it commits. Same for bulk approve and for marking a whole
attendance sheet away. If the request fails after the window closes, the screen rolls
back and says so.

The typed confirmation stays only where the action is genuinely irreversible.

## Optimistic updates

A toggle that waits on a round trip feels broken even when it works. Feature switches
move under the finger and roll back if the server disagrees; attendance marks the same.

## When something goes wrong

A wrong address used to bounce silently to the roadmap, which reads as the app ignoring
you; it now says the page is not there and that nothing is broken. Losing the connection
showed a failed request and nothing else; there is now a notice saying which of the two
it is.

## Continue watching

The player already knew its position and threw it away every time somebody closed the
tab. A two hour recap watched in three sittings is unusable without this, and hunting for
your place with a scrubber is the small friction that quietly stops people coming back.

Position is saved every fifteen seconds and again on `pagehide` and `visibilitychange`,
because closing the tab is the most common way a long video ends. One row per learner per
video, updated rather than appended: the only question anyone asks is where to resume.

Positions under twenty seconds and over ninety-five percent are not offered. Resuming at
eleven seconds is worse than starting, and a finished video should offer to start again
rather than resume at the credits. The cover reads "Continue from 10:40" with a progress
bar and a "start again" beside it.

**Continue watching** on the roadmap shows what was started and not finished, most recent
first, across chapters and session recordings both, because a half watched recap is
exactly the thing somebody means to come back to.

## Attendance and session notes

Both worked on the server since schedules were built and neither had a screen, so a
session left no trace beyond having happened. Marks save one at a time as they are
tapped, since a mentor doing this from a phone after a session should never lose the lot
to a missed save. "Mark the rest away" handles the tail.

Notes go on the session rather than on each learner, so every attendee's record carries
them without anyone copying anything, and a recording published later inherits them.

## Recording coverage

**Admin → Recording coverage**: sessions held against sessions recorded, per mentor, over
ninety days. A missing recording is otherwise invisible, because there is nothing there
to notice.

## Forgot password

Both doors carry a link. The form always answers the same way, whether that address has
an account or not, because anything else turns it into a way of finding out which
addresses are real.

The token is random, 256 bits, and **stored hashed** the way a password is: a table of
live reset tokens is a list of account takeovers for anyone who can read the database,
and the person who needs it already has it in their mail. It lasts an hour, works once,
and asking again kills the previous one, since two live tokens means two ways in.

Using it signs out every device on that account. A reset usually means something went
wrong, and if somebody else was in there, this is what removes them.

## Rate limiting

Two counters, because they stop different things.

**Per account:** five failures shuts it for fifteen minutes. A success clears the count,
so five wrong then one right then two wrong is two, not seven.

**Per address:** twenty failures a minute across all accounts. The per-account counter
never sees a sweep, because each individual account only gets a few tries.

A lockout says so plainly rather than pretending the password was wrong. Hiding it does
not slow an attacker, who can measure it anyway, and it wastes the time of somebody who
simply mistyped twice. Reset links are capped at three per address per hour.

**Admin → Access and sharing → Locked out** shows who is shut out, from which addresses,
and how long is left, with one button to clear it.

## It does not know what you teach

An LMS that names Python on screen has guessed at somebody else's course. Nothing in the
product names a subject, a tool or a language, and `tools/check-generic.py` keeps it that
way.

What that meant in practice:

| Was | Is now |
|---|---|
| "Python usually comes first" in the setup checklist | "Put whichever one everything else builds on first" |
| A Python runner on every topic | A per-topic choice, **None** by default |
| Eight fixed sections in the information form | `FormSection` documents, renamed, reordered, made optional or switched off |
| A fixed list of skills learners rate themselves on | A setting. Blank drops the question entirely |
| Placeholders naming Pandas, Power BI, Tableau | Placeholders describing the field |

The code runner is the interesting one. Offering "try it in Python" on a topic about
interview technique is noise, and on one about SQL it is wrong, so the teacher decides per
topic rather than the product assuming.

## A fresh database is empty

One super admin, and nothing else. No Python module somebody else invented, no durations
that are a formula, no task briefs saying "apply this to the practice dataset". A sample
course is worse than an empty one: it has to be found and deleted before the real course
goes in, and some of it will be missed.

```
BOOTSTRAP_EMAIL=you@proitbridge.com
BOOTSTRAP_PASSWORD=something-long-and-temporary
```

That account is created only when the database has no users at all, so restarting never
resurrects an account somebody deliberately removed, and changing the variable cannot
reset a password. It must change its password on first sign in, because whatever was in
that variable is now in a shell history and probably a deployment file.

There is no demo cohort and no seeder to switch on. The only thing that ever writes
without a request is the bootstrap: one super admin on a database with no users, and the
feature toggle catalogue, which the code branches on and is therefore structural rather
than sample content.

## Adding a person

A staff account gets the same name derived login ID and first password as a learner, both
**mailed to them** and shown once on screen to whoever created it. Once is deliberate: it
is stored only as a hash, so there is no second chance and nothing to leak later. They
must set their own on first sign in.

Mentors and admins share the learner scheme on purpose. A second rule for staff would mean
a second thing to remember on the one occasion a year somebody creates a mentor.

Typing a password into the form instead still works, for when you want to hand it over in
person.

This was a real hole until now. The password was generated, hashed and dropped: no mail,
no return value, nothing on screen. The account existed and nobody alive could sign in
to it.

## Getting started

**Super admin → Overview** is the bridge between an empty product and a bewildering
one. Ten steps in the order that avoids doing work twice, each one ticking itself off
because the thing exists rather than because somebody pressed done, and each one saying
why it matters rather than just what to click. The first unfinished step is open; the
rest stay out of the way.

**Is each course ready to teach** counts what is missing per course: topics with
nothing attached, tests with no questions, no price, no modules. It blocks nothing. A
course being half built is a normal Tuesday; not knowing which half is the problem.

## Pasting an outline

Typing forty titles one at a time is the thing that stops a real course ever being
entered. **Paste an outline** takes them whole, one per line, with an optional duration
after a pipe. The same parse serves both levels: pasted into a module it makes chapters,
into a chapter it makes topics, because an outline is an outline and the person pasting it
should not have to care which screen they are on.

```
Setting up Python and the notebook | 20
Variables, types and operators | 18
Lists, tuples, sets and dictionaries
```

Numbering and bullets are stripped, since an outline is usually pasted from a document.
Titles already present are skipped, so running the same paste twice is safe. Everything
else is filled in afterwards, which is the right order: get the shape down, then the
detail.

## Two doors

Learners sign in at `/`, staff at `/staff`, and they do not look alike.

The learner page is warm, carries the questions people actually arrive with (no mail,
forgotten password, too many devices), and says plainly that one account means one
learner. The team page is dark, plainer, has no help block, and reminds whoever is
signing in that their actions are recorded against their name.

The portal is sent with the credentials and checked **after** the password, never before.
A wrong guess gets the same answer at both doors, so nobody can map which addresses
belong to staff. Someone holding correct credentials already knows what kind of account
they have, so telling them which door to use costs nothing and saves a support message.

Signing out returns you to the door you came in by.

## Signing in

**Two ways in, one password.** Every account has a login ID derived from the person's
name (`priya.sharma`, and `priya.sharma2` when that is taken, accents folded so Ramirez
does not lose a letter) alongside their email. Either one works in the same field. Which
one somebody types depends on whether they were read it out or mailed it, and making them
remember which is a support message nobody needs.

**The first password is their name.** `priya@123`, from the first word of the full name,
for learners, mentors and admins alike. This is a deliberate trade, and it is worth being
honest about which way it cuts. A random twelve character string is safer on paper and in
practice gets mistyped four times and then screenshotted into a group chat, which is worse
than the thing it was protecting against. A password made of the person's own name is
guessable by anyone who knows their name.

What makes it defensible is that it is a one time key rather than a password:

- `mustChangePassword` is set, so nothing in the product opens until it is replaced.
- Setting it back to `name@123` is refused **by name** at the change screen, not left to
  the length rule, which it happens to pass.
- It expires unused. **Settings, First password expires after**, 14 days by default, zero
  to switch the expiry off. Without this a dormant account sits guessable forever, which
  is the one way this scheme actually goes wrong. If you turn the expiry off, that is the
  risk you are taking on.

**Reissuing.** Admins have a Reset password button on the learner register and super
admins have the same on People. It puts the account back to the name password, forces a
change again and restarts the clock, so a reissue is never weaker than the original. It is
also the honest answer to the message admins actually get, which is "I cannot log in":
whoever is on the phone can say what the password is without looking it up anywhere. The
learner's own Forgot password flow is unchanged and still mints a proper single use link.

**Where the password is visible.** Once, in a panel that stays until dismissed rather than
a toast that does not, because an admin is usually reading it to somebody. Nothing stores
the plain text, so once that panel is closed the only route back is a reset.

**The bootstrap super admin** stays the only account whose password you set yourself,
through `BOOTSTRAP_EMAIL` and `BOOTSTRAP_PASSWORD` on an empty database. It is forced to
change at first sign in too, because whatever was in that environment variable is now in a
shell history and probably a deployment file.

**Existing accounts.** `LoginIdMigration` gives everyone who predates login IDs one, and
touches no password. Somebody who already chose their own keeps it and simply gains a
shorter thing to type. Reissuing in bulk would mail the entire user base at once, which is
an admin decision rather than a migration's.

## Authentication, and four things that were wrong with it

The token itself is unremarkable: HS256, twelve hour expiry, carrying the user id, email,
role, name and the session id. What matters is what happens around it.

**A signature is not enough.** Every request re-checks that the session in the token is
still the current one, so signing out, being replaced by another device, or being
suspended kills the token immediately rather than at expiry.

Four defects found and fixed in review:

1. **An ended session answered 403, not 401.** With no authentication entry point
   configured, Spring treats an unauthenticated request as anonymous-but-forbidden. The
   client cannot tell that apart from a real permission problem, so a learner whose
   session was replaced would watch every request fail while holding a token it never
   thought to discard. Now 401, which is what tells the client to sign out.
2. **A wrong password reported as an expired session.** Sign in also answers 401, and the
   client treated every 401 the same way, so a mistyped password produced "your session
   ended" instead of "those credentials do not match". The login endpoint is now exempt
   from that handling and its own message reaches the screen.
3. **File downloads were unauthenticated.** The task thread linked straight to
   `/api/files/{id}`, and a plain link carries no bearer header, so every download would
   have come back 401. Downloads now go through the client and hand the browser a blob.
4. **A short secret failed with an obscure library error.** `JWT_SECRET` under 32 bytes
   now fails at startup naming the setting and how to generate a good one.

---

## The console

Everything a course is made of used to be literals in a seeder: the modules, the chapter
titles, the durations, the task briefs, the video ids, the test questions. Adding one real
chapter meant a code change and a deploy. The console now owns all of it, and it walks the
three levels the way somebody actually builds a course.

**Modules** is a table, one row per subject, with the chapter, topic, file and runtime
counts across it. Those counts are the answer to "which module is actually finished".

**A module** opens to its chapters as an accordion, each expanding to show the topics
inside without navigating away. Chapters reorder; the order is what the sequential unlock
walks.

**A chapter** is one screen with four things on it: the topics, then the assignment, the
test and the teach back. They are together because they are one decision. Whether a
chapter needs a test depends on what its topics turned out to cover, and splitting that
across tabs is how a chapter ends up with a test switched on and no questions in it.

**A topic** carries its video in the same form, because splitting them across two screens
is how a course ends up with topics that play nothing, and the id has to be captured
somewhere anyway. Putting a new id into a topic that already has one is a rotation, the
same operation as fixing a leak.

**Courses** bundles modules. The same module sits in more than one course, which is the
whole reason for bundling rather than copying: one subject is edited once and every course
carrying it follows.

Three rules protect people's records. A module with learner progress, or one sitting in a
course, is **archived, not deleted**: hidden from new courses, still readable for anyone
who studied it. A topic anybody has watched is archived too. A chapter with progress
refuses deletion outright, because its test scores and task history hang off that id.

Questions are written by hand or drafted by the model and edited, in the same list. A
chapter that has a test switched on and no questions is called out, and publishing a
course is refused outright while any chapter in it has no topics: a learner meeting an
empty page is the failure the readiness panel exists to prevent.

## Where files live

Local disk, under `FILES_DIR`. That is the right answer while there is one server and
somebody is backing it up.

It is also a decision you can defer, because nothing outside one folder knows about it.
Every document holds a `fileId`, every download goes through `FileService`, and
`FileService` goes through a `StorageProvider`. Moving to S3 or Cloudflare R2 is one new
class, one config line, and an `aws s3 sync` of the existing files, since the storage key
on disk is the object key in the bucket. `backend/src/main/java/com/proitbridge/lms/service/storage/README.md`
has the steps.

Move when the API runs on ephemeral storage, when there is more than one instance, or when
the material outgrows the application server. Not before.

## Reading a file without leaving the LMS

A learner who has to download a notebook, find it, and open Jupyter before they can read
what their mentor attached will mostly not bother. So every material opens in place.

| File | Shown as |
|---|---|
| `.ipynb` | The notebook as it looks in Jupyter: code cells with outputs, plots, tracebacks, markdown |
| `.pdf` | Inline, in the browser's own viewer |
| `.csv`, `.tsv` | The first fifty rows as a table, with the real row and column count |
| `.py`, `.sql`, `.js`, `.json`, `.md` | Syntax-plain, on a dark surface, with a Run button |
| `.docx` | Converted to HTML, tables included |
| Images | Inline |

Everything is fetched through the authenticated client, because a plain link carries no
bearer token and comes back refused. `mammoth` is code split, so a learner who never opens
a Word document never downloads the library that reads one.

## Python, in the browser

There is an obvious way to run learner code and it is the wrong one. A sandbox on our
server means arbitrary code from every learner on the machine that also holds the
database: containers, timeouts, memory caps, egress rules, and somebody eventually
getting out. It is a problem you keep solving.

**Pyodide runs CPython compiled to WebAssembly inside the learner's own tab.** The only
machine at risk is theirs, and there is nothing to escape into. pandas, numpy and
matplotlib are loaded; charts are pulled out of matplotlib's buffer and shown under the
output.

**Datasets attached to the topic are mounted into Python's filesystem**, so
`pd.read_csv("retail-sales.csv")` works with the filename the learner can see rather than
some path they have to be told. A cell in a notebook has a Run button that carries it
across, and so does a `.py` file.

What it costs: about ten megabytes on first use, from a CDN, and no threads or sockets.
For teaching pandas, none of that matters. When the download fails the runner says so
plainly rather than hanging.

## Two libraries, not one

**Course content is recorded.** Python, statistics, machine learning and the rest are
pre-recorded lectures, and they live on their topics as materials. Nothing about them
is live.

**Live sessions are doubt clearing, recaps, project and industry sessions.** What those
produce is a second library that grows every week, and it used to be a flat list a mentor
filled in when they remembered.

### The queue is the feature

A session whose end time has passed with no recording appears on **Mentor → Sessions to
publish**, ageing in days, flagged past a week. A publishing form only fills a library
when somebody remembers; a queue makes the missing week visible, the same way an
unreviewed task does. The title, date, length, batch and attendance all come from the
slot, so publishing is the video and a paragraph.

### Two shapes that come from how the sessions actually run

A **recap belongs to a subject**, not a topic, and runs over two or three days. It is a
series, and its parts stay together rather than scattering through a list by date.

**Doubt clearing is combined across every batch.** It is never scoped to one, because
scoping it would hide it from most of the people who were in the room. Every learner sees
every doubt session.

### Making a long recording usable

A two hour doubt session is mostly not relevant to any one learner. Each recording carries
what was covered, the session notes, and **the questions learners actually asked the
assistant that week**, taken from the doubt log, which was being written from the day the
doubt block shipped and until now was read by nobody.

### Coverage

**Admin → recording coverage** compares sessions held against sessions recorded, per
mentor, over ninety days. A gap in the library is otherwise invisible.

## Materials on a topic

A topic holds an ordered list of materials, and that order is the sequence a learner works
through. Before the topic level existed this list hung off the chapter, which meant the
notebook for one video sat next to the dataset for another.

| Kind | Carries | Learner sees |
|---|---|---|
| Video | A `Video` reference | The secure player, watermarked and watch tracked |
| Notes | An uploaded PDF or document | Download |
| Code | A notebook, script or zip | Filename, size, download |
| Dataset | A CSV or workbook | Download |
| Slides | A PDF or deck | Download |
| Link | A URL | Opens in a new tab |
| Written note | Text typed in the builder | Rendered inline |

A resource carries exactly one payload, decided by its kind, and the service refuses
anything else rather than half saving it. A dataset holding a video reference is a bug
that reaches a learner as an empty download.

Per resource: **required** decides whether it counts towards finishing, **available**
holds material back until after the session, **downloadable** covers a dataset you want
handed out and slides you do not, and **track scope** lets a premium-only resource sit
inside a shared topic.

Files go through the upload service that already existed: random storage key, type and
size checked, authorised download only. Several can be dropped at once and each becomes
its own resource, named after the file.

**Nothing written before this is stranded.** The single `videoRef` a topic carries is
folded in as the first resource the moment one is read, and written back.

## Settings

The numbers that were environment variables or literals now live in the database and are
edited in the product: organisation name, support email, the prerequisite video, the
at-risk threshold, the batch start day and the join day the Wednesday rule works from,
what counts as watched, the pass mark, the join window, the device limit, the idle
timeout. Anything showing "default" has never been set and is running on the value in the
code, and a single reset puts it back.

An environment variable is the wrong home for anything the team changes on a Tuesday
afternoon: it means a restart, and usually someone with server access.

## Pasting a video

Nobody copies an id. They copy the address bar, or the Share button, or the embed
snippet. All of those are accepted anywhere a video is set: the chapter editor, settings,
a mentor publishing a recording, an admin rotating a leaked one.

```
https://www.youtube.com/watch?v=dQw4w9WgXcQ
https://youtu.be/dQw4w9WgXcQ?si=abc123
https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s
https://www.youtube.com/embed/dQw4w9WgXcQ
https://www.youtube.com/shorts/dQw4w9WgXcQ
<iframe src="https://www.youtube.com/embed/dQw4w9WgXcQ"></iframe>
dQw4w9WgXcQ
```

Only the id is stored. The field shows what it read before you save, and a playlist, a
Drive link or anything else is refused with a reason rather than a shrug. Cloudflare
Stream links and uids parse the same way. The server parses independently of the browser,
so the check cannot be skipped.

## Where it plays

Inside the LMS, never on youtube.com. The embed uses youtube-nocookie, the title bar and
related videos are suppressed, and there is no route out to the video's own page. The
video stays unlisted, which means it is not searchable and not on your channel, but
anyone holding the link can open it directly on YouTube. That is why the id is only
handed over at play time, why every grant is logged with a name, and why rotation exists.
`CLOUDFLARE_STREAM` is the option that closes that gap properly.

## The player, everywhere

The walkthrough was a grey box and an honour-system button. It now plays through the same
grant flow as a chapter, with the same watermark and the same watch tracking, and an
admin points it at any video from Settings. The gate clears from real playback rather
than a claim.

---

## Still open from the specification

These were listed as undecided and are implemented with a sensible default that is easy
to change:

1. Post onboarding call, modules open immediately with no additional approval step.
2. Overdue tasks flag the learner as at risk but do not block the next chapter.
3. Doubt clearing runs one to one for premium and as a fixed group session for batch.
4. Mid batch placement is left to admin judgement; there is no cut off day.
5. Resume versions are kept indefinitely.
6. Certificates and payment records are not modelled yet.
7. The teach back check scores explanations with a simple rubric on the server; swapping
   in an LLM call means changing `LearnerService.conceptCheck` only.
8. A chapter's assignment holds one brief. Several assignments in one chapter would need
   the brief to become a list, and nothing has asked for that yet.
9. A login ID does not change when somebody is renamed, because they have already told
   people what theirs is. Changing one deliberately is not possible from any screen yet.
10. The first password scheme trades secrecy for something an admin can read down a phone.
    It is bounded by a forced change and an expiry, not by being hard to guess. If you
    ever need real secrecy at creation, `CredentialService.firstPasswordFor` is the one
    method to change.

---

## Note on this build

The React app was installed and built successfully (`npm run build` produces a clean
`dist/`). The Java backend was written but not compiled here, because Maven Central was
not reachable from the build sandbox. Run `mvn -q clean package` locally on first use.

The checks in `tools/` are what stands in for a compile, and all of them pass on this
build: `ParseCheck` on 170 files with no syntax errors, and zero problems from
`check-derived-queries`, `check-accessors`, `check-service-calls`, `check-scope`,
`check-java-imports`, `check-constructors`, `check-routes`, `check-icons`,
`check-imports`, `check-a11y`, `check-generic`, `check-edits` and
`check-no-sample-data`. `check-wiring` reports one call it cannot resolve, in
`TaskThread.jsx`, where the path is built from a template literal; that predates this
work.

## Ownership on a task thread

A task belongs to one learner and to the staff above them. For a while nothing enforced
that. `thread()`, `comment()` and `review()` each went through a plain lookup by id, so
an assignment id lifted from anywhere read back another learner's submissions, their
mentor's feedback, their score and the file id behind it, and let anyone comment on it
or review it. `submit()` was the only one of the five that checked, and the others now
follow its wording.

A learner gets their own task and nothing else. A mentor gets the learners they hold
plus anyone they are covering, which is the same set the desk is built from, so cover
arrangements keep working without a second rule. An admin gets everyone by definition.

`GET /api/files/{id}` had the same shape of hole: being signed in was the whole check,
which meant a file id read off a thread downloaded somebody else's notebook. Staff still
get every file, because reviewing work is the job. A learner gets their own.

## The learner side of a task

The thread was built for two roles and only ever mounted for one. A mentor could read
the whole conversation; a learner saw a table with the latest feedback string in a cell,
and a learner asked for changes had nowhere in the product to put them. Three routes
were serving nothing.

Opening a task from **Projects and tasks** now gives a learner the same thread, with a
resubmission block instead of the review block. A resubmission is a new turn, never a
replacement, which is the whole reason the thread exists.

## Admin, mentor, senior mentor

An admin runs the machine and is not a mentor. That distinction used to exist only in
the role name: the admin sidebar carried a Mentoring section straight into the mentor
desk, the task queue and attendance, so whoever enrolled a learner could also score
their work. Admin now owns everything around the teaching, which is the people, the
batches, who mentors whom, and what each learner has been granted. Adding and editing a
mentor moved down from super admin with it, because the one person who configures the
product should not also be the only one who can replace a mentor who left on a Tuesday.
Pay, granting the admin or super admin role, and deletion stay upstairs.

A batch has one mentor, chosen when the batch is created, and every learner placed in it
becomes theirs. Round robin still fills an empty seat on the premium track, where there
is no cohort to belong to.

Above a mentor sits whoever they report to. `reportsToId` has been on the user record
from the beginning and nothing read it, so a lead holding four mentors saw four empty
screens and collected their numbers by asking. Visibility is now own learners, plus
anyone being covered for, plus everyone below them in the tree. Reading down the tree is
not the same as owning the work: a senior opens any learner below them, and scoring,
approving and releasing slots stay with the mentor whose name is on the record.

Worth being clear about the consequence. Widening visibility widens the security
boundary, and a senior can read every submission below them. That is what a reporting
chain means here; if it should be narrower, the rule to change is
`MentorCoverService.mentorIdsVisibleTo`.

## Uploads

The type check was a list of MIME types, which a browser reports inconsistently: the
same `.py` arrives as `text/x-python` on one machine and `application/octet-stream` on
the next. So the product refused the scripts and notebooks it exists to collect, while
an assignment brief sat there promising it accepted them. It is an extension whitelist
now, with executables refused by extension whatever they claim to be.

Several files at a time, because almost nothing is one file. A notebook comes with the
dataset it reads and the chart it produced. Each file is its own request, so one
rejection names itself instead of losing the whole drop.

## The video link

The player embeds YouTube, and the two places it offered the real URL were the right
click menu and the logo in its own control bar. The menu is swallowed, the two corners
the player puts links in are covered, and full screen is off so the watermark cannot be
left behind.

This raises the effort. It does not make the id a secret, because it still crosses the
network, and anyone who opens developer tools will find it. What actually protects the
library is that every upload is unlisted, every play is a short lived grant tied to one
account, and a leak is answered by rotating the upload. If the library needs to be
genuinely sealed rather than inconvenient, that is Cloudflare Stream with signed URLs,
and the provider for it is already in `service/video`.
