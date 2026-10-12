// PIB LMS load test: 20,000 stored learners, 1,000 concurrently active users.
//
//   k6 run -e BASE_URL=http://localhost:8081 main.js
//
// Three scenarios run together. Every VU signs in once with its own account and keeps
// its token (learners: one live session per account, so two VUs must never share one).
import exec from 'k6/execution';
import {
  learnerAccounts, staffAccounts, newSession, ensureLogin, call, json, think, pick,
} from './lib.js';
import { report } from './report.js';

const RAMP = __ENV.RAMP || '5m';
const HOLD = __ENV.HOLD || '10m';
const DOWN = __ENV.DOWN || '2m';
const LEARNER_VUS = Number(__ENV.LEARNER_VUS || 900);
const MENTOR_VUS = Number(__ENV.MENTOR_VUS || 80);
const ADMIN_VUS = Number(__ENV.ADMIN_VUS || 20);
// shift which learner accounts are used, e.g. a second run on fresh accounts
const LEARNER_OFFSET = Number(__ENV.LEARNER_OFFSET || 0);

// mentor and admin list pages: path and query are configurable, so a paged version
// can be tested without editing this file. {batchId} and {mentorId} are filled in.
const MENTOR_ROSTER_PATH = __ENV.MENTOR_ROSTER_PATH || '/api/mentor/roster';
const MENTOR_ROSTER_QUERY = __ENV.MENTOR_ROSTER_QUERY || '';
const ADMIN_REGISTER_PATH = __ENV.ADMIN_REGISTER_PATH || '/api/admin/register';
// the register has no paging and builds a row per learner; unfiltered it walks all
// 20,000. Default is one batch, the way the page is normally used. Set to '' for all.
const ADMIN_REGISTER_QUERY = __ENV.ADMIN_REGISTER_QUERY !== undefined
  ? __ENV.ADMIN_REGISTER_QUERY : 'batchId={batchId}';

const stages = (n) => [
  { duration: RAMP, target: n },
  { duration: HOLD, target: n },
  { duration: DOWN, target: 0 },
];

// every name used below; a threshold per name makes k6 report each one separately
const LEARNER_READS = [
  'GET /api/learner/dashboard', 'GET /api/learner/this-week', 'GET /api/learner/chapters/:id',
  'GET /api/learner/topics/:id', 'GET /api/learner/watch/:ref', 'GET /api/learner/recordings',
  'GET /api/learner/slots', 'GET /api/learner/study',
];
const LEARNER_WRITES = [
  'POST /api/video/:ref/grant', 'POST /api/learner/watch/:ref', 'POST /api/learner/study/heartbeat',
];
const STAFF = [
  'GET /api/mentor/dashboard', 'GET /api/mentor/day', 'GET mentor roster', 'GET /api/mentor/learners/:id',
  'GET /api/admin/overview', 'GET /api/admin/batches', 'GET admin register',
  'GET /api/admin/onboarding-board', 'GET /api/analytics',
];
export const ENDPOINTS = ['POST /api/auth/login', ...LEARNER_READS, ...LEARNER_WRITES, ...STAFF];

const thresholds = {
  http_req_failed: [{ threshold: 'rate<0.01' }, { threshold: 'rate<0.20', abortOnFail: true, delayAbortEval: '2m' }],
  'http_req_duration{kind:learner_read}': ['p(95)<500'],
  http_req_duration: ['p(99)<2000'],
  checks: ['rate>0.99'],
  lt_login_failures: ['count<10'],
  lt_relogins: ['count<10'],
};
for (const n of ENDPOINTS) {
  // report-only rows: always pass, but give per-endpoint numbers in the summary
  thresholds[`http_req_duration{name:${n}}`] = ['max>=0'];
  thresholds[`http_req_failed{name:${n}}`] = ['rate>=0'];
}

export const options = {
  scenarios: {
    learners: { executor: 'ramping-vus', exec: 'learners', startVUs: 0, stages: stages(LEARNER_VUS), gracefulRampDown: '30s' },
    mentors: { executor: 'ramping-vus', exec: 'mentors', startVUs: 0, stages: stages(MENTOR_VUS), gracefulRampDown: '30s' },
    admins: { executor: 'ramping-vus', exec: 'admins', startVUs: 0, stages: stages(ADMIN_VUS), gracefulRampDown: '30s' },
  },
  thresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'],
  discardResponseBodies: false,
  noConnectionReuse: false,
  userAgent: 'pib-lms-loadtest/k6',
};

// ------------------------------------------------------------------------- per-VU state
// Module scope is per VU in k6, so this is each virtual user's own browser.
let me = null;
let plan = null;          // chapters/topics this learner may open, from the roadmap
const positions = {};     // videoRef -> seconds played so far
let rosterIds = [];
let batchIds = [];

function learnerAccount() {
  const n = learnerAccounts.length;
  const idx = (exec.vu.idInTest - 1 + LEARNER_OFFSET) % n;
  if (exec.vu.idInTest > n) {
    // more VUs than accounts would put two VUs on one learner and they would keep
    // replacing each other's session; refuse rather than produce nonsense
    exec.test.abort(`More VUs (${exec.vu.idInTest}) than learner accounts (${n})`);
  }
  return learnerAccounts[idx];
}

function staffAccount(roles) {
  const pool = staffAccounts.filter((a) => roles.indexOf(a.role) >= 0);
  // staff may hold several sessions (access.singleSession.staff is off by default), so
  // two VUs on one mentor account is allowed and is what 80 VUs over 60 mentors means
  return pool[(exec.vu.idInTest - 1) % pool.length];
}

// --------------------------------------------------------------------------- learners
/** Unlocked topics from the dashboard roadmap, current chapter first. */
function readPlan(dash) {
  const out = [];
  for (const m of (dash && dash.roadmap) || []) {
    for (const c of m.chapters || []) {
      if (c.locked) continue;
      const open = (c.topics || []).filter((t) => !t.locked);
      if (!open.length) continue;
      const next = open.find((t) => !t.watched) || open[open.length - 1];
      out.push({ chapterId: c.id, topicId: next.id, complete: !!c.complete });
    }
  }
  // the chapter in progress is where most time is spent; revisits are the rest
  out.sort((a, b) => Number(a.complete) - Number(b.complete));
  return out;
}

export function learners() {
  if (!me) me = newSession(learnerAccount(), 'LEARNER');
  if (!ensureLogin(me)) return;

  const dash = json(call(me, 'GET', '/api/learner/dashboard', null, 'GET /api/learner/dashboard', 'learner_read'));
  if (dash) plan = readPlan(dash);
  think();

  call(me, 'GET', '/api/learner/this-week', null, 'GET /api/learner/this-week', 'learner_read');
  think();

  const target = plan && plan.length ? (Math.random() < 0.7 ? plan[0] : pick(plan)) : null;
  if (target) {
    call(me, 'GET', `/api/learner/chapters/${target.chapterId}`, null, 'GET /api/learner/chapters/:id', 'learner_read');
    think();
    const topic = json(call(me, 'GET', `/api/learner/topics/${target.topicId}`, null, 'GET /api/learner/topics/:id', 'learner_read'));
    const t = topic && topic.topic;
    if (t && t.videoRef) {
      const ref = t.videoRef;
      const dur = Math.max(60, (t.durationMin || 15) * 60);
      // what SecurePlayer does: ask for a resume point, get a grant, then save the
      // position every 15 s of play and once on close
      call(me, 'GET', `/api/learner/watch/${ref}`, null, 'GET /api/learner/watch/:ref', 'learner_read');
      call(me, 'POST', `/api/video/${ref}/grant`, null, 'POST /api/video/:ref/grant', 'learner_write');
      call(me, 'POST', '/api/learner/study/heartbeat', { chapterId: target.chapterId }, 'POST /api/learner/study/heartbeat', 'learner_write');
      const saves = 1 + Math.floor(Math.random() * 3);
      for (let i = 0; i < saves; i += 1) {
        think();
        const at = Math.min(dur - 1, (positions[ref] || 20) + 15 + Math.floor(Math.random() * 30));
        positions[ref] = at;
        call(me, 'POST', `/api/learner/watch/${ref}`, { seconds: at, durationSec: dur, watchedSec: at },
          'POST /api/learner/watch/:ref', 'learner_write');
      }
    }
    think();
  }

  call(me, 'GET', '/api/learner/recordings', null, 'GET /api/learner/recordings', 'learner_read');
  think();
  // batch / sessions page
  call(me, 'GET', '/api/learner/slots', null, 'GET /api/learner/slots', 'learner_read');
  call(me, 'GET', '/api/learner/study', null, 'GET /api/learner/study', 'learner_read');
  think();
}

// ---------------------------------------------------------------------------- mentors
function fill(template, vars) {
  return template.replace(/\{(\w+)\}/g, (_, k) => encodeURIComponent(vars[k] || ''));
}

export function mentors() {
  if (!me) me = newSession(staffAccount(['MENTOR']), 'STAFF');
  if (!ensureLogin(me)) return;

  call(me, 'GET', '/api/mentor/dashboard', null, 'GET /api/mentor/dashboard', 'staff_read');
  think();
  call(me, 'GET', '/api/mentor/day', null, 'GET /api/mentor/day', 'staff_read');
  think();

  const q = fill(MENTOR_ROSTER_QUERY, { page: '0' });
  const roster = json(call(me, 'GET', `${MENTOR_ROSTER_PATH}${q ? `?${q}` : ''}`, null, 'GET mentor roster', 'staff_read'));
  if (roster) {
    const ids = [];
    // grouped shape today ({groups:[{learners:[...]}]}); a paged one may be {content:[...]}
    for (const g of roster.groups || []) for (const l of g.learners || []) ids.push(l.learnerId || l.id);
    for (const l of roster.content || roster.items || []) ids.push(l.learnerId || l.id);
    if (ids.length) rosterIds = ids.filter(Boolean);
  }
  think();

  const lid = pick(rosterIds);
  if (lid) {
    call(me, 'GET', `/api/mentor/learners/${lid}`, null, 'GET /api/mentor/learners/:id', 'staff_read');
    think();
  }
}

// ----------------------------------------------------------------------------- admins
export function admins() {
  if (!me) me = newSession(staffAccount(['ADMIN', 'SUPER_ADMIN']), 'STAFF');
  if (!ensureLogin(me)) return;

  call(me, 'GET', '/api/admin/overview', null, 'GET /api/admin/overview', 'staff_read');
  think();

  if (!batchIds.length) {
    const b = json(call(me, 'GET', '/api/admin/batches', null, 'GET /api/admin/batches', 'staff_read'));
    batchIds = (Array.isArray(b) ? b : (b && (b.content || b.batches)) || []).map((x) => x.id).filter(Boolean);
  }
  const q = fill(ADMIN_REGISTER_QUERY, { batchId: pick(batchIds), page: '0' });
  call(me, 'GET', `${ADMIN_REGISTER_PATH}${q ? `?${q}` : ''}`, null, 'GET admin register', 'staff_read');
  think();

  call(me, 'GET', '/api/admin/onboarding-board', null, 'GET /api/admin/onboarding-board', 'staff_read');
  think();
  call(me, 'GET', '/api/analytics', null, 'GET /api/analytics', 'staff_read');
  think();
}

export function handleSummary(data) {
  return report(data, ENDPOINTS);
}
