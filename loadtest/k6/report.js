// End-of-test report: summary.json, report.txt and report.html in RESULTS_DIR, and the
// same text on stdout. No remote imports, so it works without internet access.
const OUT = (__ENV.RESULTS_DIR || '../results').replace(/\/$/, '');
const STAMP = __ENV.RUN_ID || 'latest';

function n(v, d = 0) {
  return typeof v === 'number' && isFinite(v) ? v.toFixed(d) : '-';
}

function pad(s, w, right) {
  s = String(s);
  if (s.length >= w) return s.slice(0, w);
  return right ? ' '.repeat(w - s.length) + s : s + ' '.repeat(w - s.length);
}

function rows(data, endpoints) {
  const out = [];
  for (const name of endpoints) {
    const d = data.metrics[`http_req_duration{name:${name}}`];
    const f = data.metrics[`http_req_failed{name:${name}}`];
    if (!d || !d.values || !d.values.count) continue;
    out.push({
      name,
      count: d.values.count,
      failRate: f && f.values ? f.values.rate : 0,
      avg: d.values.avg,
      p95: d.values['p(95)'],
      p99: d.values['p(99)'],
      max: d.values.max,
    });
  }
  return out.sort((a, b) => b.p95 - a.p95);   // slowest first
}

function verdicts(data) {
  const out = [];
  for (const [metric, m] of Object.entries(data.metrics)) {
    if (!m.thresholds) continue;
    for (const [expr, t] of Object.entries(m.thresholds)) {
      // the per-endpoint rows are report-only; leave them out of the verdict list
      if (expr === 'max>=0' || expr === 'rate>=0') continue;
      out.push({ metric, expr, ok: t.ok });
    }
  }
  return out;
}

export function report(data, endpoints) {
  const list = rows(data, endpoints);
  const checks = verdicts(data);
  const pass = checks.every((c) => c.ok);
  const m = data.metrics;
  const reqs = m.http_reqs ? m.http_reqs.values : {};
  const vus = m.vus_max ? m.vus_max.values.max : '-';

  const lines = [];
  lines.push(`PIB LMS load test: ${pass ? 'PASS' : 'FAIL'}`);
  lines.push(`requests ${n(reqs.count)}  (${n(reqs.rate, 1)}/s)   max VUs ${vus}   ` +
    `failed ${n((m.http_req_failed ? m.http_req_failed.values.rate : 0) * 100, 2)}%   ` +
    `p95 ${n(m.http_req_duration && m.http_req_duration.values['p(95)'])} ms   ` +
    `p99 ${n(m.http_req_duration && m.http_req_duration.values['p(99)'])} ms`);
  lines.push(`logins ${n(m.lt_logins && m.lt_logins.values.count)}   login failures ` +
    `${n(m.lt_login_failures && m.lt_login_failures.values.count)}   re-logins ` +
    `${n(m.lt_relogins && m.lt_relogins.values.count)}`);
  lines.push('');
  lines.push('Thresholds');
  for (const c of checks) lines.push(`  ${c.ok ? 'ok  ' : 'FAIL'}  ${c.metric}  ${c.expr}`);
  lines.push('');
  lines.push('Endpoints, slowest p95 first (ms)');
  lines.push(`  ${pad('endpoint', 40)}${pad('count', 9, true)}${pad('fail%', 8, true)}${pad('avg', 8, true)}` +
    `${pad('p95', 8, true)}${pad('p99', 8, true)}${pad('max', 9, true)}`);
  for (const r of list) {
    lines.push(`  ${pad(r.name, 40)}${pad(n(r.count), 9, true)}${pad(n(r.failRate * 100, 2), 8, true)}` +
      `${pad(n(r.avg), 8, true)}${pad(n(r.p95), 8, true)}${pad(n(r.p99), 8, true)}${pad(n(r.max), 9, true)}`);
  }
  const text = `${lines.join('\n')}\n`;

  const esc = (s) => String(s).replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
  const html = `<!doctype html><html><head><meta charset="utf-8"><title>PIB LMS load test</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>body{font:14px Arial,sans-serif;margin:24px;color:#111;background:#fff}
h1{font-size:20px}.pass{color:#0a7d32}.fail{color:#E0212F}
table{border-collapse:collapse;margin:12px 0}td,th{border:1px solid #ddd;padding:4px 8px;text-align:right}
td:first-child,th:first-child{text-align:left}tr.bad td{background:#fdecee}</style></head><body>
<h1>PIB LMS load test: <span class="${pass ? 'pass' : 'fail'}">${pass ? 'PASS' : 'FAIL'}</span></h1>
<p>Run ${esc(STAMP)}. Requests ${n(reqs.count)} (${n(reqs.rate, 1)}/s), max VUs ${vus}.</p>
<h2>Thresholds</h2><table><tr><th>metric</th><th>rule</th><th>result</th></tr>
${checks.map((c) => `<tr class="${c.ok ? '' : 'bad'}"><td>${esc(c.metric)}</td><td>${esc(c.expr)}</td><td>${c.ok ? 'ok' : 'FAIL'}</td></tr>`).join('\n')}
</table><h2>Endpoints, slowest p95 first (ms)</h2>
<table><tr><th>endpoint</th><th>count</th><th>fail %</th><th>avg</th><th>p95</th><th>p99</th><th>max</th></tr>
${list.map((r) => `<tr class="${r.p95 > 500 || r.failRate > 0.01 ? 'bad' : ''}"><td>${esc(r.name)}</td><td>${n(r.count)}</td><td>${n(r.failRate * 100, 2)}</td><td>${n(r.avg)}</td><td>${n(r.p95)}</td><td>${n(r.p99)}</td><td>${n(r.max)}</td></tr>`).join('\n')}
</table></body></html>
`;

  return {
    stdout: text,
    [`${OUT}/summary-${STAMP}.json`]: JSON.stringify(data, null, 2),
    [`${OUT}/report-${STAMP}.txt`]: text,
    [`${OUT}/report-${STAMP}.html`]: html,
  };
}
