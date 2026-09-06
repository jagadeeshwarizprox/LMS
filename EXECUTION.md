# Execution guide

One path from a fresh machine to a working course with real learners on it. About an
hour, most of it waiting on downloads and typing in your own content.

`RUNBOOK.md` is the reference for every environment variable and every failure mode.
`SETUP.md` is the long form with screenshots of the reasoning. This is the short path.

**Read this first.** Maven Central has still never been reachable from a machine that
built this, so `mvn` has never run. What has run is `tools/offline-compile/verify.sh`: a
real `javac` over all 171 backend sources with the third-party API surface stubbed, at
the Java 17 target the pom sets. It resolves every symbol in our own code and reports
zero, so the errors this stage used to warn about, a missing accessor or a constructor
that gained an argument or a local declared twice, are not there.

What is still unverified is whether those stubs match the real jars. **Stage 2 is where
you find out**, and a signature that has drifted from Spring or jjwt or POI is the one
thing it can still turn up.

---

## Stage 0 — What you need

| Tool | Version | Check |
|---|---|---|
| Java JDK | 17 or newer | `java -version` |
| Maven | 3.9 or newer | `mvn -version` |
| Node | 20 or newer | `node -v` |
| MongoDB | 6 or newer | `mongod --version` |

Docker instead of all four is fine, and covered at the end.

---

## Stage 1 — Environment

From the project root:

```bash
export MONGODB_URI="mongodb://localhost:27017/pib_lms"
export JWT_SECRET="a-long-random-string-at-least-32-characters"

export BOOTSTRAP_EMAIL="you@proitbridge.com"
export BOOTSTRAP_PASSWORD="something-long-and-temporary"
export BOOTSTRAP_NAME="Your Name"
```

PowerShell uses `$env:JWT_SECRET = "..."`.

The three bootstrap variables are the ones people forget. **A fresh database has no users
at all**, so without them the product starts perfectly and nobody can sign in. They are
read only when the database is empty, so they can never reset an existing account or
resurrect one you deleted on purpose.

Leave `MAIL_ENABLED` alone for now. False is the default and it writes every credential
mail to a log you can read in the product, which is what you want until real learners
exist.

---

## Stage 2 — Compile the backend

This is the step that has never run. Do it before anything else.

```bash
cd backend
mvn clean package
```

**If it fails, read only the first error.** Errors cascade, so the tenth message is
usually caused by the first. Fix that one, run again.

| What you see | What it means |
|---|---|
| `cannot find symbol` on a getter | The accessor is missing from the document class. There is no Lombok, so every one is written out; add it |
| `constructor ... cannot be applied` | A service gained a dependency and a caller was not updated |
| `package does not exist` | A missing import at the top of the file |
| `variable X is already defined` | A duplicate local. This is the class of bug no checker catches, so it is the one to expect |

For a faster loop than a full build, from the project root:

```bash
cd ..
./tools/offline-compile/verify.sh     # the compiler, about four seconds
python3 tools/check-java-imports.py   # the structural checks, about one second each
python3 tools/check-accessors.py
python3 tools/check-constructors.py
```

Do not move on until you see `BUILD SUCCESS`.

---

## Stage 3 — Start it

Two terminals, or one script.

**The script**, from the project root:

```bash
./run.sh
```

It checks Mongo, builds, waits for the health endpoint, then starts Vite. Ctrl-C stops
both.

**By hand:**

```bash
cd backend && mvn spring-boot:run          # 8080
cd frontend && npm install && npm run dev  # 5173
```

Confirm the API before touching the UI:

```bash
curl http://localhost:8080/api/health
```

In the log you should see one super admin created. If you do not, the database was not
empty, and the bootstrap variables were ignored.

---

## Stage 4 — First sign in

Staff sign in at **http://localhost:5173/staff**. Learners at **http://localhost:5173/**.
A learner account is refused at the staff door and the other way round.

Sign in with your `BOOTSTRAP_EMAIL`. You will be made to set a new password immediately,
because whatever you put in that environment variable is now in a shell history and
probably a deployment file.

Then open **Super admin, Overview**. It lists what is left in the order that avoids
doing anything twice, and each row links to the screen that clears it.

---

## Stage 5 — Settings

**Super admin, Settings.** Four worth doing now:

| Setting | Why |
|---|---|
| Organisation name | Appears in every mail and on the sign in screen |
| Support email | Blank until you set it, because a plausible wrong address is worse than none |
| At risk after no sign in | The threshold your mentors will actually work to |
| Require onboarding before the course opens | Ships off. Off, the course opens on first sign in and the three gates are a checklist |
| First password expires after | 14 days. See Stage 8 before changing it |

---

## Stage 6 — Build one course

The content has three levels, and getting this shape right the first time saves redoing
it later.

**Module** is a subject. **Chapter** is a sitting: the group of topics tested and
assigned together. **Topic** is one video and its files.

The middle level is the one people skip. If you find yourself making a chapter per video,
those are topics of one chapter instead. Four short videos on control flow are one thing
to be tested on, not four.

1. **Modules, New module.** Name it after the subject. The code (M01) is assigned for you.
2. Open it, **Paste an outline**. One chapter per line, minutes after a pipe:
   ```
   Getting set up | 20
   Core syntax and types | 18
   Collections | 24
   ```
   Numbering and bullets are stripped. Running it twice skips what already exists.
3. Open a chapter, **Paste an outline** again. These are the topics, the actual videos.
   Three to six per chapter is normal.
4. Open each topic. Paste the **video link**: a watch link, a share link, a `youtu.be`
   link or the embed snippet all work, and the field reads back the id it extracted before
   you save. Drag in datasets, notebooks and notes here too. Switch on the **code runner**
   where a learner should write and run code.
5. Back on the chapter: **Assignment**, **Chapter test** and **Teach back**, each with an
   on/off switch. These cover every topic in the chapter together, which is the reason
   chapters exist. Switch off what the chapter does not need.
6. Write the **test questions**, or draft them with the model and edit. Nothing reaches a
   learner without a person approving it.
7. **Courses, New course.** Name, label, cover colour, price. Add the modules and their
   order, then tick who it opens to: premium, batch, self paced, sequential unlock,
   certificate.

The module table shows how many topics have nothing attached. The course readiness panel
lists what is still missing. **Publish is refused while any chapter has no topics**,
because a learner reaching an empty page is the failure that panel exists to prevent.

Then press **Check the videos** on the course. It walks every topic and reports how many
will actually play, naming each one that will not. The chain from a pasted link to a
playing video runs through the topic, the chapter, the bundle and the grant, and each
link can be individually fine while the learner still sees nothing. Take this to zero
problems before Stage 7.

Last: **Settings, Prerequisite video**. Every learner watches this before their modules
open. Without it, that gate is skipped entirely.

---

## Stage 7 — People

**Super admin, Admins and mentors, Add.** Mentors, admins, leads. Reporting is
transitive, so a lead sees everything under their whole tree.

Each row edits, resets its password, and switches off. Switching off is never a delete:
their name stays readable on mentor history and task reviews. A mentor still holding
learners is refused until you move them, which the learner count in their row does in one
step. The last active super admin cannot be switched off.

**Admin, All learners, Enrol one learner** for a learner by hand, or **Import from sheet**
for the record workbook. Both run through the same provisioning path, so they cannot drift
apart.

The enrol form asks for the name, email, phone, WhatsApp, the course picked from what you
actually built, the track, a mentor if you want one now, and the joining date. Batch
learners get a batch picker; leaving it blank places them by the Tuesday rule.

---

## Stage 8 — How logins work

Worth understanding before you hand credentials to anyone.

Every account gets a **login ID made from the name** (`priya.sharma`, and `priya.sharma2`
when that is taken) and a **first password made the same way** (`priya@123`). The login ID
or the email both work in the same field, against the same password.

This is a deliberate trade, and it cuts both ways. A random twelve character password is
safer on paper and in practice gets mistyped four times and then screenshotted into a
group chat. A password made of somebody's own name is guessable by anyone who knows their
name.

Three things are what make it acceptable, and all three matter:

- Nothing in the product opens until it is replaced.
- Setting it back to `name@123` is refused by name at the change screen, not left to the
  length rule, which it happens to pass.
- **It expires unused.** Settings, First password expires after. Without this, a dormant
  account sits guessable forever, which is the one way this scheme actually goes wrong.
  Setting it to zero switches the expiry off; that is a risk you are choosing to take.

The password is readable on screen once, in a panel that stays until you dismiss it. With
mail off, everything also lands in **Admin, Credential mail log**.

When somebody says they cannot get in, that is the **Reset password** button on the
learner register or on People. Back to the name password, forced change again, clock
restarted, so a reissue is never weaker than the original.

---

## Stage 9 — Prove it end to end

Ten minutes, before real learners arrive.

| # | Do this | Should happen |
|---|---|---|
| 1 | Enrol one test learner | Login ID and password shown on screen |
| 2 | Sign in as them with the **login ID** | Made to set their own password first |
| 3 | Sign out, sign in with their **email** | Same account, both routes work |
| 4 | Watch the walkthrough to the end | Gate clears on its own, no button |
| 5 | Fill the information form | Second gate clears |
| 6 | Mark the onboarding call done as admin | Roadmap opens. With onboarding not enforced it was already open |
| 7 | Open the first chapter | Topics listed, later ones locked |
| 8 | Open the first topic | Materials list, video watermarked with their name |
| 9 | Watch each topic to the end | Next one opens; the last hands over to the test |
| 10 | Take the test | First attempt is the recorded score |
| 11 | Submit the assignment | Appears in Mentor, Tasks |
| 12 | Review it as the mentor | Undo for ten seconds, then commits |
| 13 | Reset that learner's password | Back to `name@123`, forced change again |

If any row misbehaves, stop there. Every one of them is load bearing for somebody's first
day.

---

## Stage 10 — Before it is public

| | |
|---|---|
| `JWT_SECRET` | A real random secret, not the default |
| `MAIL_ENABLED=true` | With real SMTP credentials |
| `CORS_ORIGINS` | Your actual domain |
| HTTPS | Everywhere. Sessions and video grants both ride on it |
| `FILES_DIR` | On a disk that is backed up |
| Mongo | Authentication on, and a backup schedule |

Delete the test learner from Stage 9 before anyone real signs in.

---

## Docker instead

```bash
docker compose up
```

Mongo, the API and the built frontend. The API waits for Mongo to answer a ping, so a
first run on a cold machine does not fail on a race. The frontend is on **8081**, not
5173.

Set `BOOTSTRAP_EMAIL` and `BOOTSTRAP_PASSWORD` in your shell before bringing it up, or
you get the defaults in `docker-compose.yml`, which are fine for a look around and not for
anything else.

This still compiles the backend inside the image, so Stage 2's warning applies to the
first `docker compose up` exactly as it does to `mvn clean package`.

---

## When something is wrong

| Symptom | Cause |
|---|---|
| Nobody can sign in on a fresh database | The bootstrap variables were not set, or the database was not actually empty |
| `BUILD SUCCESS` but startup fails on a bean | A constructor argument that no static check can catch. Read the first line of the stack, not the last |
| No mail arrives | `MAIL_ENABLED` is false, which is the default. Everything is in Admin, Credential mail log |
| A learner sees a player that will not start | That topic has no video attached. Press Check the videos on the course |
| Every video says it is not part of their course | That learner has no course set. It is required at enrolment now, but older records may predate that |
| Publish is refused | A chapter in that course has no topics. The readiness panel names it |
| First password rejected | It expired unused. Reset it, which restarts the clock |
| A learner cannot join a session | Mentor, Slots and sessions, Check. Usually the link was set after the session was scheduled, or you are outside the 15 minute window |
