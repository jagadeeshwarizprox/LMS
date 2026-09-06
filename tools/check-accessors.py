"""
Same check, scoped per method. Short names like r, s, b and l are reused across
methods with different types, so a whole-file view reports shadowing as errors.
"""
import pathlib, re

root = pathlib.Path('backend/src/main/java/com/proitbridge/lms')

# Lombok is gone, so accessors are real methods. Check against what is declared,
# not against what a generator would have produced.
fields, declared = {}, {}
for f in (root / 'domain').glob('*.java'):
    src = f.read_text()
    fields[f.stem] = set(re.findall(r'private\s+(?:final\s+)?[\w<>,.\[\] ]+?\s+(\w+)\s*[;=]', src))
    declared[f.stem] = set(re.findall(r'public\s+[\w.<>,\[\]$ ]+?\s+(\w+)\s*\(', src))

def accessors(cls):
    return declared.get(cls, set())

def methods(src):
    """Crude but adequate: split on a method signature, follow braces to its end."""
    out = []
    for m in re.finditer(r'\n    (?:public|private|protected|static|final|\s)+[\w<>,.\[\] ]+\s+\w+\s*\([^;{]*\)\s*\{', src):
        i = src.index('{', m.start())
        depth, j = 0, i
        while j < len(src):
            if src[j] == '{': depth += 1
            elif src[j] == '}':
                depth -= 1
                if depth == 0: break
            j += 1
        out.append(src[m.start():j])
    return out

problems = []
for f in root.rglob('*.java'):
    if '/domain/' in str(f):
        continue
    src = f.read_text()
    # a field declared on the class is visible in every method
    class_typed = {}
    for cls in fields:
        for var in re.findall(r'private (?:final )?' + cls + r'\s+(\w+)\s*[;=]', src):
            class_typed[var] = cls

    for body in methods(src):
        typed = dict(class_typed)
        for cls in fields:
            for var in re.findall(r'\b' + cls + r'\s+(\w+)\s*[=,)]', body):
                typed[var] = cls
            for var in re.findall(r'\bfor\s*\(\s*' + cls + r'\s+(\w+)\s*:', body):
                typed[var] = cls
            for var in re.findall(r'\((?:\s*' + cls + r'\s+)(\w+)\)\s*->', body):
                typed[var] = cls
        for var, cls in typed.items():
            allowed = accessors(cls) | {'equals', 'hashCode', 'toString', 'getClass'}
            for call in set(re.findall(r'\b' + re.escape(var) + r'\.(get\w+|set\w+|is[A-Z]\w*)\s*\(', body)):
                if call not in allowed:
                    problems.append(f'{f.name}: {var} is a {cls}, no {call}()')

for p in sorted(set(problems)):
    print(p)
print('---')
print('problems:', len(set(problems)))
