# ProITBridge LMS — running it from scratch

Everything below assumes a clean machine. Roughly 30 minutes end to end, most of it
waiting on downloads.

One thing to know before you start: **the backend has still never been compiled.** The
machine that produced it has no access to Maven Central, so `mvn` could never run.

It has been put through javac's own parser and the checkers in `tools/`, which found and
fixed two bugs that would each have stopped the build on line one: every repository
interface had its entity type replaced by its own first query method, and two controllers
mapped the same path, which makes Spring refuse to start. All checks now report zero.
What they cannot verify is anything needing the Spring jars, so step 4 may still surface
a few import or signature errors. That is normal and quick to clear.

**There is no Lombok.** Every getter and setter on every document is written out. It
costs about 900 lines, and it buys the project back from a compile-time code generator:
no annotation processor to configure, no IDE that silently produces a class file full of
"method is undefined", and what you read in the file is what runs.

**Just want the shortest path that works?** Read `EXECUTION.md`. Ten stages, fresh
machine to real learners.

**Setting up for the first time?** Read `SETUP.md`. It goes from an empty machine to a working course, proves each step before the next, and ends with a checklist that exercises the whole product.

**On Windows with VS Code?** `WINDOWS.md` covers the IDE side. Same steps, with PowerShell
commands, the VS Code run configuration, and the two problems that only happen on that
stack.

---

## 1. Install the four things you need

| Tool | Version | Check with |
|---|---|---|
| Java (JDK) | 17 or newer | `java -version` |
| Maven | 3.9 or newer | `mvn -version` |
| Node | 20 or newer | `node -v` |
| MongoDB | 6 or newer | `mongod --version` |

**macOS**

```bash
brew install openjdk@17 maven node mongodb-community
brew services start mongodb-community
```

**Ubuntu or WSL**

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk maven

curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs

# MongoDB: follow the official repo steps for your release, then
sudo systemctl start mongod
sudo systemctl enable mongod
```

**Windows** — install the JDK, Maven and Node from their installers, and MongoDB from the
MSI, which registers it as a service. Everything after this works the same in PowerShell.

**Confirm Mongo is actually up** before going further:

```bash
mongosh --eval "db.runCommand({ ping: 1 })"
```

If that fails, nothing downstream will work. Fix it here.

---

## 1b. The shortcut, if you have Docker

Everything below can be skipped. From the unpacked folder:

```bash
docker compose up --build
```

That starts Mongo, builds and runs the API, and serves the built frontend behind nginx.
Open **http://localhost:8081**. First build takes a few minutes while Maven downloads;
after that it is seconds. `docker compose down -v` removes the data volumes and gives you
a clean seed on the next run.

The rest of this guide is the manual path, which is what you want while developing.

---

## 2. Unpack

```bash
unzip pib-lms.zip
cd pib-lms
```

You should see `backend/`, `frontend/` and `README.md`.

---

## 3. Set the environment

Nothing is required to start. Every setting has a working default, and the only two you
should change before real use are the JWT secret and the database name.

```bash
export JWT_SECRET="a-long-random-string-at-least-32-characters"
export MONGODB_URI="mongodb://localhost:27017/pib_lms"
```

Windows PowerShell uses `$env:JWT_SECRET = "..."`.

Full list, all optional:

| Variable | Default | What it does |
|---|---|---|
| `MONGODB_URI` | `mongodb://localhost:27017/pib_lms` | Database |
| `JWT_SECRET` | a development string | **Change this before real use** |
| `BOOTSTRAP_EMAIL` / `BOOTSTRAP_PASSWORD` / `BOOTSTRAP_NAME` | | The first super admin, created only on an empty database |
| `MAIL_ENABLED` | `false` | False writes to the mail log instead of sending |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USER` / `SMTP_PASSWORD` | | Only when mail is on |
| `CORS_ORIGINS` | `http://localhost:5173` | Where the frontend runs |
| `AI_ENABLED` | `true` | Turns the model features on |
| `OLLAMA_URL` | `http://localhost:11434` | |
| `OLLAMA_MODEL` | `llama3.1` | |
| `VIDEO_PROVIDER` | `YOUTUBE` | Or `CLOUDFLARE_STREAM` |
| `DEVICE_LIMIT` | `2` | Registered devices per account |
| `IDLE_MINUTES` | `120` | Session idle timeout |
| `FILES_DIR` | `./data/files` | Where uploads land |
| `FILES_MAX_BYTES` | `26214400` | 25 MB |

---

## 4. Build the backend

This is the step that has never run. Do it before anything else.

```bash
cd backend
mvn clean package
```

**If it fails**, read the first error only and fix that one, then run again. Errors
cascade, so the tenth message is usually caused by the first. For a faster loop without a
full build, the checkers in `tools/` run in a second each and cover syntax, Spring Data
query names, document accessors and service signatures. The likely categories:

| Error | Fix |
|---|---|
| `NoClassDefFoundError: SomeType` at startup, no package on the name | A missing import that an IDE compiled anyway. Run `python tools/check-java-imports.py` |
| `cannot find symbol` on a getter | The accessor is missing from the document class. They are all explicit now, so add it |
| `constructor ... cannot be applied` | A service gained a dependency; add it to the constructor call |
| `package does not exist` | A missing import at the top of the file |

Send me the output and I will clear them. Do not move on until you see `BUILD SUCCESS`.

---

## 5. Start the backend

```bash
mvn spring-boot:run
```

Or run the jar directly:

```bash
java -jar target/pib-lms-1.0.0.jar
```

Watch for the port it bound to, then check it:

```bash
curl http://localhost:8080/api/health
```

**There is no demo data.** A fresh database gets one super admin from `BOOTSTRAP_EMAIL`
and `BOOTSTRAP_PASSWORD`, the feature toggles the code branches on, and a first draft of
the information form. Everything else is created by you through the product, which is the
only way the catalogue ends up being the one you actually teach rather than a sample
somebody has to find and delete.

Two one time migrations also run here, each guarded by a setting so a restart cannot
repeat them: the three-level catalogue split, and the login ID backfill. On an empty
database both find nothing to do.

---

### Or use the script

`./run.sh` from the project root does steps 4 to 6 in one go: it checks Mongo is up,
builds the backend, waits for the health endpoint, then starts Vite. Ctrl-C stops both.

---

## 6. Start the frontend

New terminal, from the project root:

```bash
cd frontend
npm install
npm run dev
```

Open **http://localhost:5173**. Vite proxies `/api` to port 8080, so no CORS setup is
needed in development.

---

## 7. Sign in and look around

**A fresh database has no accounts.** Set these before the first start:

```bash
export BOOTSTRAP_EMAIL="you@proitbridge.com"
export BOOTSTRAP_PASSWORD="something-long-and-temporary"
```

That creates one super admin, which must change its password on first sign in. Then work
down **Super admin → Setting up**, which lists what is left in the order that avoids
doing anything twice.

There is no sample data and nothing to switch on. Every account after the first is
created by you, through the product.

Learners sign in at **http://localhost:5173/**, staff at **http://localhost:5173/staff**.
A learner account is refused at the staff door and the other way round.

Everyone you create gets a login ID and a first password made from their own name:
Priya Sharma becomes `priya.sharma` with the password `priya@123`. Either the login ID or
their email works in the field, and they are made to choose their own password before
anything opens. That first password also stops working if the account is never used, after
the number of days in **Settings, First password expires after**.

With `MAIL_ENABLED=false`, which is the default, every credential mail lands in
**Admin, Credential mail log**, so you can read the details back without a mail server.
Both the create screen and the reset button also show them on screen once.

| Role | Signs in with | What to look at |
|---|---|---|

A five minute tour that touches most of the product:

1. Sign in as the mentor. **My desk** shows today, the ageing queue, and where the cohort is stuck
2. **My learners** — filter to at risk, click a name for the drawer
3. **Tasks** — open a thread, score the rubric, send a review
4. Sign in as the premium learner. **This week** at the top of the roadmap
5. Press **Check in** in the top bar, then open **My study time**
6. Open a chapter: work through its topics, ask a doubt, take notes
7. Sign in as the super admin. **Modules**, open a module, expand a chapter, open a topic, and paste a YouTube link into the video field

---

## 8. Turn on the model features (optional)

Without Ollama the LMS still works: teach-back falls back to a length rubric, and the
doubt block says the assistant is unreachable and points at the doubt session. Nobody is
ever blocked mid chapter.

```bash
curl -fsSL https://ollama.com/install.sh | sh
ollama pull llama3.1
ollama serve
```

Then restart the backend. If you use a different model, set `OLLAMA_MODEL` to match.

---

## 9. Set up your own content

Nothing here needs a code change. The seeded course exists so the screens have something
to show; replace it from inside the product.

### Sign in as the super admin

the bootstrap account you created

### Settings first

**Configure → Settings.** Fourteen values that used to be environment variables, each
with a note saying what it does. Anything marked "default" has never been set.

Set at least these before anyone real signs in:

| Setting | Why |
|---|---|
| Organisation name, support email | They appear in every mail |
| Prerequisite video | The walkthrough every learner watches first. Press **Set the video** and paste a YouTube link |
| Batches start on, join day for the previous batch | The Wednesday rule is derived from these two |
| At risk after no sign in | The threshold your mentors will actually work to |
| Devices per account | Two is deliberate. Raising it makes sharing easier |

### Then the course

**Modules**, then **Courses**.

1. **New module** for each subject you teach
2. Open it and **Paste an outline** to create its **chapters**. A chapter is a sitting, not a video: it is the group of topics tested and assigned together
3. Open a chapter and paste an outline again for its **topics**. These are the videos, usually three to six per chapter
4. Open each topic and paste the **video link**. A watch link, a share link, a `youtu.be` link or the embed snippet all work, and the field reads back the id it extracted before you save. Drag in any datasets or notes here too
5. Back on the chapter, turn off any of **Assignment**, **Chapter test** or **Teach back** it does not need, and write the assignment brief if it has one. These cover every topic in the chapter together
6. Add the **test questions**: write them yourself, or draft with the model and edit. Nothing a learner sees is published without a person approving it
7. **Courses, New course**, set the price, add the modules and their order, and tick who it opens to. **Publish** is refused while any chapter has no topics

The module table says how many topics have nothing attached, and the course readiness
panel lists what is still missing. A learner reaching an empty topic sees a player that
refuses to start, so take those to zero before enrolling anyone.

### What is protected

A module with learner progress is archived, not deleted: hidden from new courses, still
readable for anyone who studied it. A chapter with progress refuses deletion outright,
because removing it would rewrite what those learners did. Both tell you which is
happening before you confirm.

### The rest

| Where | What |
|---|---|
| **Configure → Questions and answers** | Thirty seeded FAQs. Edit anything that does not match how you work |
| **Configure → Track features** | What premium gets that batch does not |
| **Mentor → Slots and sessions** | Each mentor pastes their real Zoom link once |
| **Mentor → Repeating sessions** | Confirm the days. Seeded as Mon and Tue doubt clearing, Sat projects, Wed live |
| **Configure → Settings → Community links** | Your WhatsApp groups |

**Still unanswered:** the lifecycle flowchart says batch doubt clearing runs Mon / Tue /
Sat, and your narration said Mon / Tue / Wed. The seeded schedule follows the flowchart.
Change it under Repeating sessions if that is wrong.

## 10. Going to production

Order matters here.

```bash
# 1. build the frontend
cd frontend && npm run build          # output lands in dist/

# 2. build the backend
cd ../backend && mvn clean package
```

Serve `frontend/dist` from Nginx or any static host, and proxy `/api` to the Spring jar.
Run the jar under systemd or a container.

**Before real learners sign in:**

| | |
|---|---|
| `JWT_SECRET` | A real random secret, not the default |
| `MAIL_ENABLED=true` | With real SMTP credentials |
| `CORS_ORIGINS` | Your actual domain |
| HTTPS | Everywhere. Sessions and video grants both ride on it |
| `FILES_DIR` | A path on a disk you actually back up |
| Mongo backups | `mongodump` on a schedule, tested by restoring once |

---

## Troubleshooting

| Symptom | Cause |
|---|---|
| Backend exits at boot | Mongo is not running, or `MONGODB_URI` is wrong |
| Login returns 403 immediately | The account is suspended, or the device limit is hit. Admin → Access and sharing releases a device |
| Signed out when opening a second tab | Working as intended. One live session per account |
| A video refuses to play | The seeded ids are placeholders. Paste a real link in the course builder |
| A pasted video link is refused | Playlists and Drive links do not play. Open the video itself and copy its link |
| No mail arrives | `MAIL_ENABLED` is false. Everything is in Admin → Credential mail log |
| Teach-back says "graded by rubric" | Ollama is unreachable. That is the fallback, not a failure |
| Uploads rejected over 25 MB | Raise both `FILES_MAX_BYTES` and the Spring multipart limits together |

---

## What is not built yet

So nobody discovers these the hard way.

**No screen yet:** attendance and session notes (the endpoints exist), project briefs,
structured mock feedback, a batch view for mentors, admin reports, certificates, in-app
notifications, forgot password, a rubric editor, and screens for job posts and case
studies.

**Still in code:** email bodies are string literals in `MailService.java` rather than
editable templates.

**No test suite.** There is none, of any kind.

**Never compiled.** See the note at the top. Step 4 is where that gets settled.
