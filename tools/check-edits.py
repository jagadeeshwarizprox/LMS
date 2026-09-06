"""
A field the frontend reads that the backend never sends.

This is the failure my other checkers cannot see, and the one that has bitten twice:
an edit silently matches nothing, everything still compiles, and the feature is simply
absent at runtime. Both sides look fine on their own.
"""
import pathlib, re

back = pathlib.Path('backend/src/main/java/com/proitbridge/lms')
front = pathlib.Path('frontend/src')

# what the learner dashboard actually puts into its payload
sent = set()
for f in back.rglob('*.java'):
    src = f.read_text()
    sent |= set(re.findall(r'\b(?:out|m|x|row|chapterView)\.put\("(\w+)"', src))
    sent |= set(re.findall(r'Map\.of\(\s*"(\w+)"', src))
    sent |= set(re.findall(r'"(\w+)",\s*[\w.]+\(', src))

# what the learner context hands to a screen
ctx = (front / 'context' / 'LearnerContext.jsx').read_text()
consumers = []
for f in (front / 'pages').rglob('*.jsx'):
    text = f.read_text()
    for m in re.finditer(r'const \{([^}]*)\} = useLearner\(\)', text):
        for name in m.group(1).split(','):
            name = name.strip()
            if name and name not in ('loading', 'error', 'reload', 'can', 'raw'):
                consumers.append((f.name, name))

problems = []
for fname, name in consumers:
    if name not in sent:
        problems.append(f'{fname}: reads "{name}" from the learner payload, '
                        f'which no backend put() sends')

for p in sorted(set(problems)): print(p)
print('---')
print('problems:', len(set(problems)))
