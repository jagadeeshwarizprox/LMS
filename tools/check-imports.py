"""
A helper used but never imported.

Vite resolves at runtime, so this builds cleanly and throws the moment the branch that
uses it renders. That is the worst kind of failure: invisible until a learner opens the
one screen that needs it.
"""
import pathlib, re

root = pathlib.Path('frontend/src')

# the shared helpers that get used without being brought in
HELPERS = ['fmtDate', 'fmtDateTime', 'Card', 'Page', 'Stat', 'Tag', 'StatusTag', 'Empty',
           'TrackTag', 'Tip', 'Icon', 'Avatar', 'Drawer', 'downloadFile', 'api',
           'useToast', 'useDialog', 'useAuth', 'useLearner', 'SecurePlayer', 'Materials']

problems = []
for f in root.rglob('*.jsx'):
    src = f.read_text()
    body = re.sub(r'^import[^\n]*\n', '', src, flags=re.M)
    imported = set()
    for m in re.finditer(r'^import\s+(?:(\w+)\s*,?\s*)?(?:\{([^}]*)\})?\s*from', src, re.M):
        if m.group(1): imported.add(m.group(1))
        if m.group(2): imported |= {x.strip().split(' as ')[-1] for x in m.group(2).split(',') if x.strip()}

    for h in HELPERS:
        # a component is <Name ...> or <Name/>; a word inside markup text is not
        used = (re.search(r'<' + h + r'[\s/>]', body)
                or re.search(r'\b' + h + r'\s*\(', body))
        if used and h not in imported:
            # a local declaration is fine
            if re.search(r'\b(const|function|let)\s+' + h + r'\b', body): continue
            problems.append(f'{f.relative_to(root)}: uses {h} but never imports it')

for p in sorted(set(problems)): print(p)
print('---')
print('problems:', len(set(problems)))
