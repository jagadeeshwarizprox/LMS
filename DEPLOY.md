# Going live

One server with Docker. Three containers: `web` (nginx and the built frontend, the only
one with a public port), `api` (Spring Boot), `mongo` (password protected, internal only).

## Before the first deploy

1. On a machine with internet access, from `backend/`: `mvn clean package`. It must pass.
2. Copy `.env.example` to `.env` and set at least:
   - `JWT_SECRET` (`openssl rand -base64 48`). The API refuses to start with a default secret once `LMS_BASE_URL` is not localhost.
   - `MONGO_PASSWORD`
   - `BOOTSTRAP_EMAIL`, `BOOTSTRAP_PASSWORD` (first super admin; change the password on first sign in)
   - `LMS_BASE_URL` and `CORS_ORIGINS`: the public https address, for example `https://lms.proitbridge.com`
   - Mail: `MAIL_ENABLED=true` and the `SMTP_*` values, or credentials are only logged, not sent
3. `docker compose up -d --build`, then `docker compose ps`: all three must say healthy.
4. Put HTTPS in front of port 8081 (Caddy, nginx, or the cloud load balancer) and point the domain at it.

## After it is up

- Sign in at `/staff`, change the bootstrap password, add admins and mentors.
- Click through each role once: learner, mentor, admin, super admin.
- Backups: the data lives in two volumes, `pib-mongo-data` and `pib-files`. Back up both, daily. For Mongo: `docker exec pib-mongo mongodump -u pib -p "$MONGO_PASSWORD" --authenticationDatabase admin --archive > backup-$(date +%F).archive`

## Limits of this setup

- **One API instance only.** Uploaded files sit on the `pib-files` volume, some state is held in memory, and the nightly jobs would run once per copy. Scaling out needs S3 storage and a job lock first.
- **Hosting the frontend and API separately** (for example on Railway): build the frontend with `VITE_API_BASE` set to the API's https URL, or keep same-origin by setting the web container's `API_UPSTREAM` to the API URL and `NGINX_RESOLVER` to a DNS server that platform allows.

## Sizing

Target: 20,000 learners stored, 1,000 people active at the same time, one server.

| Server | Fits | Settings |
|---|---|---|
| 4 vCPU / 8 GB RAM | 1,000 active (recommended minimum) | `mem_limit: 3g` on `api`, `MONGO_CACHE_GB=2` |
| 8 vCPU / 16 GB RAM | headroom for peaks and growth | `mem_limit: 6g` on `api`, `MONGO_CACHE_GB=5` |

These are starting points, not measurements. Confirm them with the load test on the real
server before go-live: `./loadtest/run.sh` (see `loadtest/README.md`). Pass means under 1%
errors, learner pages p95 under 500 ms, everything p99 under 2 s.

What is tuned, and where:

- **API threads**: Tomcat 200 threads, accept queue 200, 10 s connection timeout
  (`application.yml`, override with `TOMCAT_MAX_THREADS`, `TOMCAT_ACCEPT_COUNT`).
- **Mongo pool**: `maxPoolSize=100` in `MONGODB_URI` in `docker-compose.yml` (`MONGO_POOL_MAX`).
  Keep it at or below half the Tomcat threads unless Mongo has spare CPU.
- **JVM**: heap is 75% of the container limit (`JAVA_OPTS`, G1). Uncomment `mem_limit` on
  `api` so the heap is sized from it; without a limit the JVM sizes from the whole host.
- **Mongo memory**: `--wiredTigerCacheSizeGB ${MONGO_CACHE_GB:-2}`. Mongo's default would take
  about half the RAM and starve the API on an 8 GB box.
- **nginx** (done, in `frontend/Dockerfile`): the stock `nginx:alpine` has
  `worker_connections 1024`, and each proxied request holds two connections, so it topped
  out near 500 concurrent requests. The image now raises it to 4096 and sets
  `worker_rlimit_nofile 8192` in `/etc/nginx/nginx.conf` (the build fails if either sed
  misses), and `nginx.conf.template` sets `keepalive_timeout 30s`. Not done: an
  `upstream api { server api:8080; keepalive 64; }` pool, because the API address is a
  variable (so nginx starts before the API is up) and nginx does not pool variable upstreams.

Known hot spots to watch in the load test report: every authenticated request reads and
writes the `sessions` row; the learner dashboard builds the roadmap with a query per chapter;
the mentor roster computes stats per learner. Whole-collection reads in services and
controllers are policed by `tools/check-scans.py`.
