# From nothing to a working LMS

Windows and VS Code. Every command is PowerShell. Roughly an hour, most of it waiting on
downloads and typing your own course in.

Work top to bottom. Each part proves itself before the next one starts, so when something
breaks you know exactly which step it was.

---

# Part 1 — Install

Open **PowerShell as administrator**:

```powershell
winget install EclipseAdoptium.Temurin.17.JDK
winget install Apache.Maven
winget install OpenJS.NodeJS.LTS
winget install MongoDB.Server
```

**Close that window. Open a new one.** winget updates PATH but not the window you
installed from, which is the single most common reason `mvn` is "not recognised".

### Prove it

```powershell
java -version      # 17 or newer
mvn -version
node -v            # 20 or newer
mongod --version
```

All four must answer. If any does not, stop here.

---

# Part 2 — The database

```powershell
net start MongoDB
Test-NetConnection localhost -Port 27017
```

`TcpTestSucceeded : True` is what you need. Nothing below works without it.

### Start clean

If you ran an earlier build of this project, whatever it wrote is still in there. Nothing
in this build deletes it, and the bootstrap super admin is only created when the database
has no users at all, so a leftover database means no new super admin appears.

```powershell
mongosh pib_lms --eval "db.dropDatabase()"
```

### Prove it

```powershell
mongosh pib_lms --eval "db.getCollectionNames()"
```

Empty array. That is what you want.

### What appears on the first start, and nothing else

| Collection | What |
|---|---|
| `users` | One super admin, forced to change its password |
| `features` | Fourteen toggles with their default per track |

The feature toggles are not sample content: the code branches on those keys, so a
database without them turns every optional feature off and a learner quietly loses mocks,
projects and job posts with nothing on screen to explain why. Every one is editable under
**Track features**, and a toggle you switch off stays off across restarts.

No modules, no topics, no questions, no courses, no batches, no learners, no FAQs, no
rubrics, no schedules, no rooms.

There is no seeder to switch on and no sample mode. `python tools\check-no-sample-data.py`
enforces it: nothing writes content without a request, and no default is a value that
looks real. A plausible wrong URL is worse than a blank one, because nothing flags it and
the first learner gets the dead link.

---

# Part 3 — Build

```powershell
cd $HOME\Downloads
Expand-Archive pib-lms.zip -DestinationPath .
cd pib-lms\backend
mvn clean package
```

**This is the step that has never run on my side**, so it is where surprises live. If it
fails, read only the first error and fix that one. Errors cascade; the tenth message is
usually caused by the first.

For a faster loop while fixing, the checkers in `tools\` run in about a second each and
cover most of what a compiler would tell you:

```powershell
cd ..
python tools\check-java-imports.py
python tools\check-constructors.py
python tools\check-scope.py
python tools\check-wiring.py
```

### Prove it

`BUILD SUCCESS`, and `backend\target\pib-lms-1.0.0.jar` exists.

---

# Part 4 — First start

The database has no accounts. Create the first one:

```powershell
$env:BOOTSTRAP_EMAIL = "you@proitbridge.com"
$env:BOOTSTRAP_PASSWORD = "temporary-and-long-enough"
$env:JWT_SECRET = "a-random-string-of-at-least-32-characters"

cd backend
java -jar target\pib-lms-1.0.0.jar
```

Watch the log for:

```
First run: created the super admin you@proitbridge.com
```

### Prove it

New terminal:

```powershell
Invoke-RestMethod http://localhost:8080/api/health
```

---

# Part 5 — The frontend

New terminal, from the project root:

```powershell
cd frontend
npm install
npm run dev
```

Open **http://localhost:5173/staff** — the team door. Sign in with the bootstrap
credentials. You will be made to set a real password immediately, because whatever was
in that environment variable is now in your shell history.

### Prove it

You land on the course builder and it is empty. That is correct.

---

# Part 6 — Set it up

Go to **Overview**. The readiness strip and the decision queue say what is left, in the order that avoids doing work twice.

## 6.1 Settings

**Configure → Settings.** The ones that matter before anyone is enrolled:

| Setting | Why |
|---|---|
| Organisation name, support email | They appear in every mail |
| Batches start on | Tuesday, unless you have changed it |
| Join day that goes to the previous batch | Wednesday. These two drive the Wednesday rule |
| At risk after no sign in | The threshold your mentors will work to |
| Devices per account | Two is deliberate |

## 6.2 Your first module

**Modules, New module.** Name it after the subject you teach. Modules carry a code
(M01, M02) assigned for you.

## 6.3 The chapters

Open the module and **Paste an outline**. One per line, duration after a pipe:

```
Getting set up | 20
Core syntax and types | 18
Collections | 24
Control flow and loops | 21
```

Numbering and bullets are stripped. Running it twice skips what is already there.

A chapter is a sitting: the group of topics that get tested and assigned together, not
one video. If you find yourself writing a chapter per video, they are probably topics of
one chapter instead.

## 6.4 The topics

Open a chapter and **Paste an outline** again, the same format. These are the actual
videos. One chapter usually holds three to six.

Then open each topic:

- **Video**: upload to YouTube as **unlisted**, paste the link. Watch links, share links,
  `youtu.be` links and embed snippets all work; the field reads back the id it extracted
- **Code**, **Dataset**, **Notes**: drag files in, several at once
- **Written note**: a short instruction shown inline
- **Code runner**: switch on where the learner should write and run code

This is the step that turns an outline into a course. The module table shows how many
topics have nothing attached.

## 6.5 The assessment

Still on the chapter, below the topics: **Assignment**, **Chapter test** and **Teach
back**, each with an on/off switch. They cover every topic in the chapter together, which
is the reason chapters exist.

- **Assignment**: title, brief, marks, days to submit, what you accept, and any files the
  brief needs
- **Chapter test**: pass mark and attempts, then write the questions or draft them with
  the model and edit. Nothing reaches a learner without a person approving it
- **Teach back**: the prompt the learner explains against

Switch off whatever the chapter does not need. A test switched on with no questions is
called out.

## 6.6 The course

**Courses, New course.** Name, label, cover colour, price. Then add modules and set their
order, and tick who it opens to: premium, batch, self paced, sequential unlock,
certificate.

The readiness panel lists what is still missing. **Publish** is refused while any chapter
has no topics, because a learner reaching an empty page is the failure that panel exists
to prevent.

## 6.7 The walkthrough

**Settings → Prerequisite video → Set the video.** Every learner watches this before their
modules open. Without it, that gate is skipped.

## 6.8 Mentors

**Admins and mentors → Add.** Their login ID and first password are made from their
name, mailed, and **shown once on screen**. Copy them, since mail is off until you
configure SMTP and the panel is your only route. They sign in with either that login ID or
their email, and must set their own password before anything opens.

## 6.9 Meeting rooms

Sign in as each mentor → **Slots and sessions** → paste their real Zoom link. The join
link is fetched at click time from the room, so a mentor without one cannot run a session.

## 6.10 Repeating sessions

**Mentor → Repeating sessions.** Monday, Tuesday and Saturday doubt clearing, plus your
project session. Slots generate two weeks ahead and refresh nightly.

## 6.11 Batches and learners

**Admin → Batches** to create one, then **Import from sheet** for your learners, or
**Enrol one learner** by hand to try the flow first.

Enrolling by hand asks for the name, email, phone and WhatsApp, the course picked from
what you actually built, the track, and a mentor if you want one now. Batch learners get
a batch picker; leaving it blank places them by the Tuesday rule. The login ID and
password appear before you create the account and again after, since that is the one
moment the password is readable.

---

# Part 7 — Prove it end to end

Do this before real learners arrive. It takes ten minutes and catches everything that
matters.

| # | Do this | Should happen |
|---|---|---|
| 1 | Import or add one learner | Login ID and password shown on screen, and in **Admin → Credential mail log** |
| 2 | Sign in as them at `http://localhost:5173/` with the **login ID** | Asked to set their own password first |
| 3 | Sign out, sign in again with their **email** | Same account, so both routes work |
| 4 | Watch the walkthrough to the end | Gate clears on its own, no button |
| 5 | Fill the information form | Second gate clears |
| 6 | Mark the onboarding call done as admin | Roadmap opens |
| 7 | Open the first chapter | Its topics are listed, later ones locked |
| 8 | Open the first topic | Materials list. Video plays with your name watermarked |
| 9 | Open the notebook | Renders in place, no download |
| 10 | Press **Try it in Python** | Downloads once, then runs. Your dataset is readable by filename |
| 11 | Watch each topic to the end | The next one opens, and the last hands over to the test |
| 12 | Take the test | First attempt is the recorded score |
| 13 | Submit the assignment | Appears in **Mentor → Tasks** |
| 14 | Review it as the mentor | Undo appears for ten seconds, then commits |
| 15 | Check the learner's **Messages** | The review note is there |
| 16 | Close the video halfway, reopen | Offers **Continue from** |
| 17 | Mark attendance after a session | Saves per tap |
| 18 | Press **Cmd-K** or **Ctrl-K** | Palette finds the learner by name |
| 19 | Reset that learner's password as admin | Back to `name@123`, forced change again |

If any of these fails, that is a specific bug worth sending me rather than a general
worry.

---

# Part 8 — Before it is public

| | |
|---|---|
| `JWT_SECRET` | A real random secret, permanently set, not per-session |
| `MAIL_ENABLED=true` | With real SMTP, or nobody gets credentials |
| `CORS_ORIGINS` | Your actual domain |
| HTTPS | Everywhere |
| `FILES_DIR` | A disk you back up |
| Mongo backups | `mongodump` on a schedule, tested by restoring once |

Permanent variables:

```powershell
[Environment]::SetEnvironmentVariable('JWT_SECRET', 'your-real-secret', 'User')
```

---

# Part 9 — What is knowingly not connected

You were right to suspect this. `python tools\check-wiring.py` lists it exactly; here is
the summary.

**Built on the server, no button anywhere:**

| What | Route |
|---|---|
| Message a whole cohort | `POST /mentor/broadcast` |
| Rubric editor | `/super/catalogue/rubrics` |
| Mentor batch view | `GET /mentor/batches/{id}/roster` |
| Batch announcements | `POST /admin/announcements` |
| Fast-forward a learner | `POST /admin/learners/{id}/fast-forward` |
| Mentor change history | `GET /admin/learners/{id}/mentor-history` |
| Jobs and case studies editors | `/super/catalogue/jobs`, `/case-studies` |
| Unpublish a recording | `DELETE /mentor/recordings/{id}` |

**Not built at all:** results page, scoring model, portfolio, certificates, Top 10,
in-app notifications, project briefs, structured mock feedback, admin reports, bulk
operations, export.

**No test suite of any kind.** Everything above was verified by hand.

Run `python tools\check-wiring.py` yourself any time. It compares all 183 routes against
all 151 frontend calls in both directions, and it is the check that found most of the
disconnections you were sensing.
