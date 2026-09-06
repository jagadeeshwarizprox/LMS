"""
A type used but never imported, and an import nothing uses.

Both come from the same place: an edit that changes the body of a file and leaves the
import block behind. The first will not compile. The second is harmless but it is the
tell that something was moved and not finished, which is worth seeing.
"""
import pathlib, re

root = pathlib.Path('backend/src/main/java/com/proitbridge/lms')

# types that need no import
BUILTIN = {
    'String','Integer','Long','Double','Boolean','Object','Math','System','Exception',
    'RuntimeException','IllegalStateException','IllegalArgumentException','Override',
    'Deprecated','SuppressWarnings','Character','Byte','Short','Float','Number','Thread',
    'Iterable','Comparable','Runnable','Class','Void','StringBuilder','Record','Enum',
    'FunctionalInterface','SafeVarargs','NumberFormatException','ClassCastException',
    'NullPointerException','UnsupportedOperationException','Throwable','Error','Process'
}

def strip(src):
    """
    Comments and string bodies out, in one pass.

    Doing it with two regexes is what broke the first version: stripping // first
    turns the // inside https:// into a comment and swallows the rest of the line,
    which then merges the strings on either side. Only a single scanner that knows
    which state it is in gets this right.
    """
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            out.append('""')
            i += 1
            while i < n and src[i] != '"':
                i += 2 if src[i] == '\\' else 1
            i += 1
        elif c == "'":
            out.append("' '")
            i += 1
            while i < n and src[i] != "'":
                i += 2 if src[i] == '\\' else 1
            i += 1
        elif src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
        elif src.startswith('/*', i):
            j = src.find('*/', i)
            i = n if j < 0 else j + 2
        else:
            out.append(c)
            i += 1
    return ''.join(out)


problems = []
# every type declared anywhere, including nested ones: a record inside an interface
# is reachable unqualified by anything implementing it
ALL_TYPES = set()
for f in root.rglob('*.java'):
    ALL_TYPES.add(f.stem)
    ALL_TYPES |= set(re.findall(r'\b(?:class|interface|enum|record)\s+(\w+)', f.read_text()))

for f in root.rglob('*.java'):
    src = f.read_text()
    package = re.search(r'package ([\w.]+);', src).group(1)
    siblings = {p.stem for p in (root / package.replace('com.proitbridge.lms.', '').replace('.', '/')).glob('*.java')} \
        if (root / package.replace('com.proitbridge.lms.', '').replace('.', '/')).exists() else set()

    imports, wildcards = {}, []
    for m in re.finditer(r'^import (?:static )?([\w.]+)(?:\.\*)?;', src, re.M):
        full = m.group(1)
        if m.group(0).rstrip(';').endswith('.*') or '.*' in m.group(0):
            wildcards.append(full)
        else:
            imports[full.split('.')[-1]] = full

    body = strip(re.sub(r'^(package|import)[^\n]*\n', '', src, flags=re.M))

    # anything after a dot is a member, not a type: HttpStatus.CONFLICT is one import,
    # not two. ALL_CAPS is a constant. Nested types are declared in the file itself.
    used = set(re.findall(r'(?<![.\w])([A-Z][A-Za-z0-9_]*)\b', body))
    declared_here = set(re.findall(r'\b(?:class|interface|enum|record)\s+(\w+)', body))

    for t in sorted(used):
        if t in BUILTIN or t in imports or t in siblings or t in ALL_TYPES:
            continue
        if t in declared_here or t.isupper():
            continue
        # a wildcard import might cover it; this check cannot see inside one, so
        # anything with a wildcard in scope is given the benefit of the doubt
        if wildcards:
            continue
        problems.append(f'{f.name}: uses {t} but nothing imports it')

    for short, full in imports.items():
        if not re.search(r'\b' + re.escape(short) + r'\b', body):
            problems.append(f'{f.name}: imports {full} and never uses it')

missing = sorted({p for p in problems if 'but nothing imports it' in p})
unused = sorted({p for p in problems if 'never uses it' in p})

for p in missing: print(p)
print('---')
print('will not compile:', len(missing))
print('unused imports, harmless but untidy:', len(unused))
if unused and __import__('sys').argv[-1] == '--all':
    print()
    for p in unused: print(p)
