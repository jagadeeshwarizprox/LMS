"""
Every screen's calls against what each role is allowed to call.

check-wiring proves a path exists. It cannot see that the path refuses the person on
the screen. That gap produced four bugs at once: a mentor opening a chapter got a page
that never loaded, a mentor's recording picker came up empty, an admin could not see
or draft chapter test questions, and a mentor was shown a join check that only the
office may run. Each one was a page open to a role that the endpoint behind it was not.

How it works: App.jsx gives the roles each page is open to, SecurityConfig gives the
roles each endpoint accepts (method-specific rules included, first match wins, exactly
as Spring reads them), and every api call in each page is checked against both.

A page may legitimately call an endpoint some of its roles cannot reach, if the call
only happens for the roles that can. Those calls are listed in GATED below with the
variable that gates them, and the script confirms the variable is still in the file.
Anything else is reported.

Run from the project root:  python3 tools/check-roles.py
"""
import pathlib, re, sys

root = pathlib.Path('.')
fe = root / 'frontend/src'
sec = (root / 'backend/src/main/java/com/proitbridge/lms/security/SecurityConfig.java').read_text()
app = (fe / 'App.jsx').read_text()

ALL = {'LEARNER', 'MENTOR', 'ADMIN', 'SUPER_ADMIN'}

# (page, endpoint prefix, the gate the page uses, the roles that gate lets through).
# A refusal of a role the gate lets through is still a bug, and is still reported.
OFFICE = {'ADMIN', 'SUPER_ADMIN'}
GATED = [
    ('pages/admin/LiveBoard', '/admin/batches', 'catch(() => setBatches([]))', OFFICE),
    ('pages/admin/Register', '/super/learners/', 'isSuper', {'SUPER_ADMIN'}),
    ('pages/mentor/LearnerRecord', '/admin/learners/', 'staff', OFFICE),
    ('pages/mentor/MentorSlots', '/slots/join-check', 'office', OFFICE),
    ('pages/superadmin/ChapterDetail', '/super/chapters/', 'authoring', OFFICE),
    ('pages/superadmin/ChapterDetail', '/super/catalogue/chapters', 'authoring', OFFICE),
    ('pages/superadmin/ChapterDetail', '/super/catalogue/topics', 'authoring', OFFICE),
    ('pages/superadmin/ChapterDetail', '/super/catalogue/questions', 'authoring', OFFICE),
]

# ---- pages and the roles they are open to -------------------------------------
lazy = dict(re.findall(r"const (\w+) = lazy\(\(\) => import\('\./(pages/[^']+)'\)\)", app))
page_roles = {}
for m in re.finditer(r"<Guard roles=\{\[([^\]]*)\]\}><(\w+) />", app):
    f = lazy.get(m.group(2))
    if f:
        page_roles.setdefault(f, set()).update(re.findall(r"'(\w+)'", m.group(1)))

# ---- endpoint rules, in order --------------------------------------------------
rules = []
for m in re.finditer(
        r'\.requestMatchers\(((?:HttpMethod\.\w+,\s*)?(?:"[^"]*"\s*,?\s*)+)\)\s*'
        r'\.(permitAll|authenticated|hasRole|hasAnyRole)\(([^)]*)\)', sec):
    method = re.search(r'HttpMethod\.(\w+)', m.group(1))
    method = method.group(1) if method else None
    if method == 'OPTIONS':
        continue
    pats = re.findall(r'"([^"]*)"', m.group(1))
    roles = ALL if m.group(2) in ('permitAll', 'authenticated') else set(re.findall(r'"(\w+)"', m.group(3)))
    rules.append((method, pats, roles))


def allowed(path, verb):
    for method, pats, roles in rules:
        if method and method != verb:
            continue
        for p in pats:
            rx = '^' + re.escape(p).replace(r'/\*\*', '(/.*)?').replace(r'\*', '[^/]+') + '$'
            if re.match(rx, path):
                return roles
    return ALL       # anyRequest().authenticated()


VERB = {'get': 'GET', 'post': 'POST', 'put': 'PUT', 'del': 'DELETE', 'upload': 'POST'}
problems = 0
for page, roles in sorted(page_roles.items()):
    path = fe / (page + '.jsx')
    src = path.read_text()
    for m in re.finditer(r"api\.(get|post|put|del|upload)\(\s*([`'\"])(.*?)\2", src):
        raw = m.group(3)
        url = '/api' + re.sub(r'\$\{[^}]*\}', 'X', raw).split('?')[0]
        refused = roles - allowed(url, VERB[m.group(1)])
        if not refused:
            continue
        hit = next(((g, ok) for p, prefix, g, ok in GATED if p == page and raw.startswith(prefix)), None)
        gate = hit[0] if hit else None
        if hit and gate in src and not (refused & hit[1]):
            continue
        line = src[:m.start()].count('\n') + 1
        problems += 1
        print(f"{page}.jsx:{line}  {VERB[m.group(1)]} {raw}")
        print(f"    page is open to {sorted(roles)}, endpoint refuses {sorted(refused)}")
        if hit and gate not in src:
            print(f"    listed as gated by '{gate}', which is no longer in the file")
        elif hit:
            print(f"    gated by '{gate}', but that gate lets {sorted(refused & hit[1])} through")

print('---')
print(f"pages: {len(page_roles)} | rules: {len(rules)} | problems: {problems}")
sys.exit(1 if problems else 0)
