# Load test: 20,000 learners, 1,000 active at once

What it proves: one server running the compose stack serves 900 learners, 80 mentors and
20 admins at the same time (each signed in once with their own account) with under 1% errors,
learner pages p95 under 500 ms and every request p99 under 2 s.

## Run it

1. Stack up and healthy: `docker compose up -d --build`, then `docker compose ps`.
2. Smoke first (2 min, 50 users): `./loadtest/run.sh seed && ./loadtest/run.sh smoke`
3. Full run (about 17 min): `./loadtest/run.sh test`

`./loadtest/run.sh` alone seeds (if not already) and runs the full test. Seeding takes a few
minutes and runs in a `python:3.12-slim` container on the compose network (needs PyPI). With
local Python instead, publish Mongo's port and run
`SEED_MODE=local MONGODB_URI='mongodb://pib:PASS@127.0.0.1:27017/pib_lms?authSource=admin' ./loadtest/run.sh seed`.

Seeded: 1 super admin, 5 admins, 60 mentors, 20,000 learners (70% in 40 batches, 30% premium),
3 courses, 12 modules, 80 chapters, 400 topics (fake YouTube ids), about 290k progress rows,
145k watch positions, 45k assignments, 480 slots for next week, 80 recordings. All accounts
are `lt-*@loadtest.local`, password `LoadTest!2026`. Logins are in `loadtest/data/*.csv`.

## Pass or fail

The run ends with `PASS` or `FAIL` and exits 0 or 99. Reports land in `loadtest/results/`:
`report-<run>.html` (open it), `report-<run>.txt`, `summary-<run>.json`, `docker-stats-<run>.txt`.

| Threshold | Means |
|---|---|
| `http_req_failed rate<0.01` | under 1% of requests errored |
| `http_req_duration{kind:learner_read} p(95)<500` | learner pages fast |
| `http_req_duration p(99)<2000` | nothing slow, staff pages included |
| `lt_login_failures`, `lt_relogins` under 10 | sessions held; nobody was signed out |

The endpoint table is sorted slowest p95 first. Red rows are the ones to look at.

## If it fails

- **Slow rows**: the top of the endpoint table names them. Turn on the Mongo profiler, run the
  smoke again, and list the slow queries:
  ```
  docker exec -it pib-mongo mongosh -u pib -p "$MONGO_PASSWORD" --authenticationDatabase admin pib_lms \
    --eval 'db.setProfilingLevel(1,{slowms:50})'
  ... then: db.system.profile.find({},{ns:1,millis:1,planSummary:1,command:1}).sort({millis:-1}).limit(20)
  ... off again: db.setProfilingLevel(0)
  ```
  `COLLSCAN` in `planSummary` means a missing index. `progress`, `assignments`, `devices`,
  `quiz_attempts`, `bookings` and `learners.mentorId/batchId` have none declared in the domain
  classes. To prove an index fixes it: `./loadtest/run.sh wipe && SEED_INDEXES=1 ./loadtest/run.sh`,
  then add the matching `@CompoundIndex` in code.
- **Errors**: `docker compose logs api --since 20m | grep -E 'ERROR|Exception'`. 401s or
  re-logins mean sessions were dropped; 409 on login means the device limit was hit.
- **CPU or memory pinned**: see `docker-stats-<run>.txt` and the Sizing section in `DEPLOY.md`.
- Paged roster or register: `MENTOR_ROSTER_PATH`, `MENTOR_ROSTER_QUERY`, `ADMIN_REGISTER_PATH`,
  `ADMIN_REGISTER_QUERY` (`{batchId}` and `{page}` are filled in). The register defaults to one
  batch, because unfiltered it builds a row for all 20,000 learners.

## Clean up

`./loadtest/run.sh wipe` deletes only seeded data and rows the test created for those accounts
(sessions, devices, login attempts, progress, watch positions, activity log). Real data is untouched.
