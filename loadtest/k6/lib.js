// Shared helpers: CSV loading, sign in once per VU, tagged requests, re-login on 401.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';

export const BASE = (__ENV.BASE_URL || 'http://localhost:8081').replace(/\/$/, '');
export const THINK_MIN = Number(__ENV.THINK_MIN || 3);
export const THINK_MAX = Number(__ENV.THINK_MAX || 8);

export const logins = new Counter('lt_logins');
export const loginFailures = new Counter('lt_login_failures');
export const relogins = new Counter('lt_relogins');

function parseCsv(text) {
  const lines = text.split(/\r?\n/).filter((l) => l.trim() !== '');
  const head = lines.shift().split(',');
  return lines.map((l) => {
    const cells = l.split(',');
    const row = {};
    head.forEach((h, i) => { row[h.trim()] = (cells[i] || '').trim(); });
    return row;
  });
}

export const learnerAccounts = new SharedArray('learners', () =>
  parseCsv(open(__ENV.LEARNERS_CSV || '../data/learners.csv')));

export const staffAccounts = new SharedArray('staff', () =>
  parseCsv(open(__ENV.STAFF_CSV || '../data/staff.csv')));

export function think() {
  sleep(THINK_MIN + Math.random() * (THINK_MAX - THINK_MIN));
}

/** One per VU: the account, its fixed device id, and the token once signed in. */
export function newSession(account, portal) {
  return {
    email: account.email,
    password: account.password,
    portal,
    // stable per account, so a re-run re-uses the registered device instead of using up
    // the per-account device limit (access.deviceLimit.learner, default 3)
    deviceId: `lt-k6-${account.email.split('@')[0]}`,
    token: null,
  };
}

export function login(s) {
  const res = http.post(`${BASE}/api/auth/login`, JSON.stringify({
    email: s.email, password: s.password, portal: s.portal,
    deviceId: s.deviceId, deviceLabel: 'k6 load test',
  }), {
    headers: { 'Content-Type': 'application/json', 'X-Device-Id': s.deviceId },
    tags: { name: 'POST /api/auth/login', kind: 'login' },
  });
  logins.add(1);
  const ok = check(res, { 'login 200': (r) => r.status === 200 && !!r.json('token') });
  if (!ok) {
    loginFailures.add(1);
    s.token = null;
    return false;
  }
  s.token = res.json('token');
  return true;
}

/** Sign in only when this VU has no live token: never once per iteration. */
export function ensureLogin(s) {
  if (s.token) return true;
  if (login(s)) return true;
  sleep(5);   // back off; a failing login loop would only add noise
  return false;
}

function params(s, name, kind) {
  return {
    headers: {
      Authorization: `Bearer ${s.token}`,
      'X-Device-Id': s.deviceId,
      'Content-Type': 'application/json',
    },
    tags: { name, kind },
  };
}

/**
 * A request as the browser makes it. `name` groups URLs with ids in them so the
 * report has one row per endpoint. A 401 means the session ended (replaced, idle or
 * revoked): sign in once more and retry, and count it, because in this test it
 * should never happen.
 */
export function call(s, method, path, body, name, kind) {
  const url = `${BASE}${path}`;
  const payload = body === undefined || body === null ? null : JSON.stringify(body);
  let res = http.request(method, url, payload, params(s, name, kind));
  if (res.status === 401) {
    relogins.add(1);
    s.token = null;
    if (login(s)) res = http.request(method, url, payload, params(s, name, kind));
  }
  check(res, { [`${name} 2xx`]: (r) => r.status >= 200 && r.status < 300 });
  return res;
}

export function json(res) {
  try { return res.json(); } catch (e) { return null; }
}

export function pick(xs) {
  return xs && xs.length ? xs[Math.floor(Math.random() * xs.length)] : null;
}
