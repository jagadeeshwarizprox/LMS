"""
A final field that is never assigned, or a constructor parameter that is accepted and
then dropped.

Both are compile errors, and both are exactly what a careless edit to a constructor
leaves behind: the field list and the parameter list get updated, the assignments do
not. This is the check that should have caught SuperAdminController.
"""
import pathlib, re

root = pathlib.Path('backend/src/main/java/com/proitbridge/lms')
problems = []

for f in root.rglob('*.java'):
    src = f.read_text()
    if 'interface ' in src.split('\n')[0:1] and 'class ' not in src:
        continue

    fields = re.findall(r'private final [\w<>,.\[\]? ]+\s+(\w+)\s*;', src)

    for m in re.finditer(r'\n    public\s+' + f.stem + r'\s*\(([^)]*)\)\s*\{', src, re.S):
        start = src.index('{', m.end() - 1)
        depth, j = 0, start
        while j < len(src):
            if src[j] == '{': depth += 1
            elif src[j] == '}':
                depth -= 1
                if depth == 0: break
            j += 1
        body = src[start:j]

        params = []
        for raw in re.split(r',(?![^<>]*>)', m.group(1)):
            raw = raw.strip()
            if not raw: continue
            params.append(raw.split()[-1])

        for p in params:
            if not re.search(r'this\.\w+\s*=\s*' + re.escape(p) + r'\b', body) \
               and not re.search(r'\b' + re.escape(p) + r'\b', body):
                problems.append(f'{f.name}: constructor takes {p} and never uses it')

        for fld in fields:
            if not re.search(r'this\.' + re.escape(fld) + r'\s*=', body):
                problems.append(f'{f.name}: final field {fld} is never assigned')

for p in sorted(set(problems)): print(p)
print('---')
print('problems:', len(set(problems)))
