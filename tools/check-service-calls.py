"""
Same check with a real paren walker. The previous regex stopped at the first
close paren, so any call containing a nested call was miscounted.
"""
import pathlib, re

root = pathlib.Path('backend/src/main/java/com/proitbridge/lms')

public = {}
for f in root.rglob('*.java'):
    src = f.read_text()
    sigs = {}
    # methods on an interface carry no modifier, since they are public by definition.
    # Matching only on "public" made every interface look like it had no methods.
    is_interface = re.search(r'public\s+interface\s+' + f.stem + r'\b', src) is not None
    pattern = (r'\n    (?:public\s+|default\s+|static\s+)*(?:<[^>]+>\s+)?[\w<>,.\[\]?\s]+?\s+(\w+)\s*\('
               if is_interface else
               r'\n    public\s+(?:static\s+)?(?:<[^>]+>\s+)?[\w<>,.\[\]?\s]+?\s+(\w+)\s*\(')
    for m in re.finditer(pattern, src):
        name = m.group(1)
        i = src.index('(', m.end() - 1)
        depth, j = 0, i
        while j < len(src):
            if src[j] == '(': depth += 1
            elif src[j] == ')':
                depth -= 1
                if depth == 0: break
            j += 1
        params = src[i + 1:j].strip()
        n = 0
        if params:
            n, depth = 1, 0
            for ch in params:
                if ch in '(<[': depth += 1
                elif ch in ')>]': depth -= 1
                elif ch == ',' and depth == 0: n += 1
        sigs.setdefault(name, set()).add(n)
    public[f.stem] = sigs

def count_args(src, open_idx):
    depth, j = 0, open_idx
    while j < len(src):
        if src[j] in '([': depth += 1
        elif src[j] in ')]':
            depth -= 1
            if depth == 0: break
        j += 1
    inner = src[open_idx + 1:j]
    if not inner.strip():
        return 0, j
    n, depth, instr = 1, 0, False
    k = 0
    while k < len(inner):
        ch = inner[k]
        if ch == '"' and (k == 0 or inner[k - 1] != '\\'):
            instr = not instr
        elif not instr:
            if ch in '([{<': depth += 1
            elif ch in ')]}>': depth -= 1
            elif ch == ',' and depth == 0: n += 1
        k += 1
    return n, j

problems = []
for f in root.rglob('*.java'):
    src = f.read_text()
    holders = {var: typ for typ, var in
               re.findall(r'private final (\w+(?:Service|Provider|Storage))\s+(\w+);', src)}
    for var, typ in holders.items():
        if typ not in public:
            continue
        for m in re.finditer(r'\b' + re.escape(var) + r'\.(\w+)\s*\(', src):
            call = m.group(1)
            sigs = public[typ]
            if call not in sigs:
                problems.append(f'{f.name}: {var}.{call}() does not exist on {typ}')
                continue
            n, _ = count_args(src, src.index('(', m.end() - 1))
            if n not in sigs[call]:
                problems.append(f'{f.name}: {var}.{call}() called with {n}, '
                                f'{typ} declares {sorted(sigs[call])}')

for p in sorted(set(problems)):
    print(p)
print('---')
print('problems:', len(set(problems)))
