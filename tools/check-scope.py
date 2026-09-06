"""
A method that touches a field its own class does not have.

This is what a careless de-Lombok pass leaves behind: the accessors for the outer
class end up inside a nested one, where the fields they name are out of reach. It
parses cleanly, so the syntax checker is happy, and it fails at compile with
"cannot make a static reference to a non-static field" or "cannot be resolved".
"""
import pathlib, re

root = pathlib.Path('backend/src/main/java/com/proitbridge/lms')


def strip(src):
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            out.append('""'); i += 1
            while i < n and src[i] != '"':
                i += 2 if src[i] == '\\' else 1
            i += 1
        elif src.startswith('//', i):
            j = src.find('\n', i); i = n if j < 0 else j
        elif src.startswith('/*', i):
            j = src.find('*/', i); i = n if j < 0 else j + 2
        else:
            out.append(c); i += 1
    return ''.join(out)


def blocks(src):
    """Every class body in the file, innermost first, with its own text only."""
    found = []
    for m in re.finditer(r'\b(?:class|enum|record)\s+(\w+)', src):
        start = src.find('{', m.end())
        if start < 0:
            continue
        depth, j = 0, start
        while j < len(src):
            if src[j] == '{':
                depth += 1
            elif src[j] == '}':
                depth -= 1
                if depth == 0:
                    break
            j += 1
        found.append((m.group(1), start, j))
    return found


problems = []
for f in root.rglob('*.java'):
    src = strip(f.read_text())
    found = blocks(src)
    for name, start, end in found:
        body = src[start:end]
        inner = [(s - start, e - start) for (_, s, e) in found if s > start and e < end]

        def outside_inner(pos):
            return not any(a <= pos <= b for a, b in inner)

        fields = {m.group(1) for m in re.finditer(r'private\s+(?:final\s+|static\s+)*[\w<>,.\[\]? ]+\s+(\w+)\s*[;=]', body)
                  if outside_inner(m.start())}
        params_ok = set()

        for m in re.finditer(r'\b(?:public|private|protected)\s+[\w<>,.\[\]? ]+\s+\w+\s*\(([^)]*)\)\s*\{', body):
            if not outside_inner(m.start()):
                continue
            mstart = src.index('{', start + m.end() - 1)
            depth, j = 0, mstart
            while j < len(src):
                if src[j] == '{': depth += 1
                elif src[j] == '}':
                    depth -= 1
                    if depth == 0: break
                j += 1
            mbody = src[mstart:j]
            params = {p.strip().split()[-1] for p in m.group(1).split(',') if p.strip()}

            for hit in re.finditer(r'\bthis\.(\w+)\b', mbody):
                if hit.group(1) not in fields:
                    problems.append(f'{f.name}: {name} touches this.{hit.group(1)}, which it does not declare')
            for hit in re.finditer(r'return\s+(\w+)\s*;', mbody):
                v = hit.group(1)
                if v in params or v in fields or v in ('true', 'false', 'null', 'this'):
                    continue
                if re.search(r'\b\w+\s+' + v + r'\s*[=;]', mbody):
                    continue
                problems.append(f'{f.name}: {name} returns {v}, which it does not declare')

for p in sorted(set(problems)): print(p)
print('---')
print('problems:', len(set(problems)))
