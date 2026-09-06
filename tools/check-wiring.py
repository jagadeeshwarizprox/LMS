"""
Every call the frontend makes against every route the backend serves, both ways.

Two failures live here and nothing else can see them. A call to a path that does not
exist fails at runtime with a 404 nobody notices until a learner presses the button.
A route nothing calls is work that was built and never connected, which is worse,
because it looks finished.
"""
import pathlib, re

back = pathlib.Path('backend/src/main/java/com/proitbridge/lms/web')
front = pathlib.Path('frontend/src')


def norm(p):
    p = re.sub(r'\{[^}]+\}', '{}', p)
    p = re.sub(r'\$\{[^}]*\}', '{}', p)
    p = p.split('?')[0].rstrip('/')
    return p or '/'


# ---- what the backend serves -------------------------------------------------
routes = {}
for f in back.glob('*.java'):
    src = f.read_text()
    m = re.search(r'@RequestMapping\("([^"]+)"\)', src)
    base = m.group(1) if m else ''
    for verb, path in re.findall(
            r'@(Get|Post|Put|Delete|Patch)Mapping(?:\(\s*(?:value\s*=\s*)?"([^"]*)"[^)]*\))?', src):
        full = norm((base + (path or '')).replace('//', '/'))
        routes[(verb.upper(), full)] = f.stem

# ---- what the frontend calls -------------------------------------------------
calls = {}
for f in front.rglob('*.js*'):
    src = f.read_text()
    for verb, path in re.findall(r'api\.(get|post|del|put|upload)\(\s*[`\'"]([^`\'"]+)', src):
        v = {'get': 'GET', 'post': 'POST', 'del': 'DELETE', 'put': 'PUT', 'upload': 'POST'}[verb]
        calls.setdefault((v, norm('/api' + path)), set()).add(f.name)
    # downloads and uploads that go through fetch rather than the client
    for path in re.findall(r'\$\{BASE\}(/[\w/${}-]+)', src):
        calls.setdefault(('GET', norm('/api' + path)), set()).add(f.name)

missing = []
for (v, p), where in sorted(calls.items()):
    if (v, p) not in routes:
        # a path with an id segment may be declared with a different placeholder shape
        loose = [(rv, rp) for (rv, rp) in routes if rv == v and rp.count('/') == p.count('/')
                 and all(a == b or a == '{}' or b == '{}' for a, b in zip(rp.split('/'), p.split('/')))]
        if not loose:
            missing.append(f'{v} {p}  called by {", ".join(sorted(where))}')

called_paths = {(v, p) for (v, p) in calls}
unused = []
for (v, p), owner in sorted(routes.items()):
    if (v, p) in called_paths:
        continue
    loose = [(cv, cp) for (cv, cp) in called_paths if cv == v and cp.count('/') == p.count('/')
             and all(a == b or a == '{}' or b == '{}' for a, b in zip(p.split('/'), cp.split('/')))]
    if not loose:
        unused.append(f'{v} {p}  ({owner})')

print('CALLED BUT NOT SERVED')
for x in missing: print('  ' + x)
print(f'  none' if not missing else '')
print()
print('SERVED BUT NEVER CALLED')
for x in unused: print('  ' + x)
print()
print('---')
print(f'routes: {len(routes)} | frontend calls: {len(calls)} | '
      f'broken: {len(missing)} | unused: {len(unused)}')
