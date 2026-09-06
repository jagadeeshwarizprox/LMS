"""
Two handlers on one path is an ambiguous mapping and Spring refuses to start. This
matches @GetMapping with or without parentheses, which an earlier version did not,
and that is exactly how a collision slipped through.
"""
import pathlib, re, collections

root = pathlib.Path('backend/src/main/java/com/proitbridge/lms/web')
routes = collections.defaultdict(list)
for f in root.glob('*.java'):
    s = f.read_text()
    m = re.search(r'@RequestMapping\("([^"]+)"\)', s)
    base = m.group(1) if m else ''
    for verb, path in re.findall(
            r'@(Get|Post|Put|Delete|Patch)Mapping(?:\(\s*(?:value\s*=\s*)?"([^"]*)"[^)]*\))?', s):
        full = (base + (path or '')).replace('//', '/').rstrip('/') or '/'
        routes[(verb, re.sub(r'\{[^}]+\}', '{}', full))].append(f.stem)

dupes = {k: v for k, v in routes.items() if len(v) > 1}
for k, v in sorted(dupes.items()):
    print(f'{k[0].upper()} {k[1]} declared in {v}')
print('---')
print('routes:', len(routes), '| duplicates:', len(dupes))
