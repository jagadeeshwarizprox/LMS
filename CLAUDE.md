# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

ProITBridge LMS: React 18 + Vite + Bootstrap 5 frontend (`frontend/`), Spring Boot 3.3 / Java 17 backend (`backend/`), MongoDB. `README.md` explains *why* things are built the way they are; `EXECUTION.md` / `SETUP.md` / `WINDOWS.md` are step-by-step setup; `DEPLOY.md` / `RUNBOOK.md` cover production. Each release is summarized in a dated `CHANGES-YYYY-MM-DD.md` at the repo root.

## Commands

```bash
# Mongo (dev)
docker run -d --name pib-mongo -p 27017:27017 mongo:7

# Backend (http://localhost:8080, health at /api/health)
cd backend && mvn spring-boot:run
cd backend && mvn clean package          # the only authoritative compile check

# Frontend (http://localhost:5173, Vite proxies /api -> :8080)
cd frontend && npm install && npm run dev
cd frontend && npm run build

# Everything locally (needs Mongo on 27017): ./run.sh  or  .\run.ps1
# Full stack in Docker (needs .env from .env.example): docker compose up -d --build
```

A fresh database has **no users**. Set `BOOTSTRAP_EMAIL` / `BOOTSTRAP_PASSWORD` (run scripts default them) to create the one super admin, who signs in at `/staff`. `JWT_SECRET` must be ≥32 bytes or the app fails at startup.

There is no automated test suite (no `src/test`, no frontend test runner, no linter config). Verification is done with the static checkers in `tools/` (see `tools/README.md`):

```bash
./tools/offline-compile/verify.sh        # real javac over the backend with stubbed 3rd-party APIs, ~4s
python3 tools/check-wiring.py            # every frontend call vs every backend route, both ways
python3 tools/check-roles.py             # page roles in App.jsx vs SecurityConfig rules
python3 tools/check-derived-queries.py   # Spring Data method names vs document fields (bad ones kill startup)
python3 tools/check-routes.py            # duplicate handler mappings (Spring refuses to start)
python3 tools/check-scans.py             # unbounded findAll() on collections that grow with learners
python3 tools/check-generic.py           # UI copy naming a subject/tool/language (forbidden)
python3 tools/check-no-sample-data.py    # anything writing content at startup besides bootstrap
```

Others in `tools/`, same invocation: `check-imports.py` (frontend helper used but not imported: Vite builds fine, then it throws at render), `check-edits.py` (frontend reads a field no backend `put()` sends), `check-icons.py`, `check-a11y.py`, `check-accessors.py`, `check-service-calls.py`, `check-constructors.py`, `check-java-imports.py`, `check-scope.py`.

Each script prints findings and a count; some have long-standing known findings, so compare against the previous run rather than expecting zero. Run the relevant ones after a change, and `mvn clean package` when Maven Central is reachable (stubs in `tools/offline-compile/stubs` can drift from the real jars). `check-generic.py` exempts files by name, so renaming a file can drop its exemption.

On Windows, `python3` may be the Microsoft Store stub; use `python`. Run `verify.sh` from Git Bash.

Each batch of work ends with a dated `CHANGES-YYYY-MM-DD.md` at the root (suffix `b` for a second one the same day), describing each change by the screen it affects and stating which checks were run and which were not.

Load testing (k6, 20k seeded learners) lives in `loadtest/`; see `loadtest/README.md`.

## Architecture

**Backend** (`backend/src/main/java/com/proitbridge/lms/`): `domain/` Mongo documents → `repo/` Spring Data repositories → `service/` business logic → `web/` REST controllers. No Lombok: domain classes have hand-written accessors, and services use constructor injection (a new dependency needs a matching constructor argument). `config/` holds `BootstrapSeeder` plus one-time migrations, each guarded by a setting so a restart cannot re-run them.

- **Roles**: `LEARNER`, `MENTOR`, `ADMIN`, `SUPER_ADMIN`. Access is by URL prefix in `security/SecurityConfig.java` (`/api/learner/**`, `/api/mentor/**`, `/api/admin/**`, `/api/super/**`, with method-specific exceptions). Frontend route guards are in `frontend/src/App.jsx`; the two must agree (`check-roles.py`).
- **Sessions**: JWT plus server-side session validation on every request (one live session per account, device limit via `X-Device-Id` header).
- **Catalogue**: three levels, `CourseModule` > `Chapter` > `Topic` (> resources). The Java class is `CourseModule` because `java.lang.Module` clashes; JSON/collections say "module". A chapter's brief is `Chapter.AssignmentSpec` because `Assignment` is a learner submission. Quiz questions, attempts, assignments and progress are keyed on `chapterId`.
- **Provisioning**: no self sign-up. `ImportService` (xlsx) and manual enrolment both go through `ProvisioningService`; email is the unique key, and batch→premium upgrades happen in place. Login IDs and first passwords are derived from the name; `mustChangePassword` blocks every route.
- **Onboarding gates**: `Learner.gatesCleared()` is the true state of the gates; `Learner.modulesUnlocked()` is what all enforcement points (including the video grant) check, and it consults the static `GatePolicy` flag.
- **Progress**: stored on the learner record, written only by `ProgressSnapshotService`; catalogue changes mark learners stale for recompute. Don't recompute progress by scanning collections.
- **Video**: provider IDs live only on `Video` documents and are never returned by any API except `POST /api/video/{ref}/grant`, which re-derives entitlement and logs a `VideoAccessLog`. Providers are behind `service/video/VideoProvider` (`YOUTUBE` or `CLOUDFLARE_STREAM`).
- **Meetings**: slots point at a mentor's room; `roomForSlot` resolves the link at read time. Join links come from `POST /api/slots/{id}/join` within the time window.
- **AI**: all model calls go through `OllamaService`; every feature must fall back to rules when `AI_ENABLED=false` or Ollama is unreachable.
- **Mail**: always logged to `mail_log` (visible in Admin); only sent when `MAIL_ENABLED=true`.
- **Files**: `FileService` stores bytes under random keys in `FILES_DIR`, never using the filename as a path.
- **Content sheet** (`service/contentsheet/`): an Excel/Google Sheet that drives the catalogue incrementally. `ContentSheetRow` keeps a per-cell hash per row (key: module+chapter+title); only changed cells are applied, through `CatalogueService`/`ResourceService`. It never deletes, and a failed cell's hash is not recorded so it retries. `LinkFetcher` is the only place the server fetches arbitrary URLs: https only, private addresses refused on every hop. Mongo map keys can't contain dots, so links are hashed before use as keys.
- Premium vs batch differences are **feature toggles** (seeded, editable by super admin, overridable per learner), not code branches per track.
- Deployment assumes **one API instance** (local files, in-memory state, nightly jobs).

**Frontend** (`frontend/src/`): `api/client.js` is the single fetch wrapper (base `VITE_API_BASE` or `/api`, adds token and device id; mind its 401 handling). State via Context (`AuthContext`, `LearnerContext`, `ToastContext`, `ThemeContext`). Pages are grouped by role in `pages/{learner,mentor,admin,superadmin}`. Shared UI primitives are in `components/Ui.jsx` and hand-drawn SVG charts in `components/Charts.jsx` (no chart library); design tokens and motion rules in `styles/theme.css`. Icons go through a name map (`check-icons.py`), and icon-only buttons need accessible names (`check-a11y.py`). `api/validators.js` mirrors server-side `Validate.java`; the server is the source of truth.

## Project rules

- **The product must not know what it teaches.** No subject, tool or language names in UI copy, placeholders or defaults; that content belongs in admin-entered data.
- **No sample/demo data.** Nothing may write content on startup except the bootstrap super admin and the structural feature-toggle catalogue.
- Moves between tracks, batches and courses never delete history, and each requires a recorded reason.
- Avoid unfiltered `findAll()` on learner-scale collections; query by learner/mentor/batch/course/status and page lists. The target is 20k learners and 1k concurrent users.
