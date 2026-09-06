"""
Two checks the parser cannot do and the Spring jars are not needed for.

1. Spring Data derives every repository query from the method name. If a name
   references a property the document does not have, the whole application fails
   to start, not just that call. So every findBy/countBy/existsBy is checked
   against the document's real fields.

2. Every call made on a repository field is checked against that interface's
   declared methods plus what MongoRepository inherits.
"""
import pathlib, re, sys

root = pathlib.Path('backend/src/main/java/com/proitbridge/lms')

# ---- domain fields, including inherited enum/nested names -------------------
fields = {}
for f in (root / 'domain').glob('*.java'):
    src = f.read_text()
    names = set(re.findall(r'private\s+(?:final\s+)?[\w<>,.\[\] ]+?\s+(\w+)\s*[;=]', src))
    fields[f.stem] = names

INHERITED = {
    'save', 'saveAll', 'findById', 'existsById', 'findAll', 'findAllById', 'count',
    'deleteById', 'delete', 'deleteAll', 'deleteAllById', 'insert', 'flush'
}

KEYWORDS = ('OrderBy', 'IgnoreCase', 'Containing', 'StartingWith', 'EndingWith',
            'Between', 'LessThan', 'GreaterThan', 'After', 'Before', 'IsNull',
            'NotNull', 'Not', 'In', 'True', 'False', 'Desc', 'Asc')

problems = []
repo_methods = {}

for f in sorted((root / 'repo').glob('*Repository.java')):
    src = f.read_text()
    m = re.search(r'MongoRepository<(\w+), String>', src)
    if not m:
        problems.append(f'{f.name}: cannot read the entity type')
        continue
    entity = m.group(1)
    if entity not in fields:
        problems.append(f'{f.name}: entity {entity} has no document class')
        continue

    declared = set()
    for line in src.splitlines():
        mm = re.match(r'\s+[\w<>,.\[\] ]+\s+(\w+)\s*\(', line)
        if not mm:
            continue
        name = mm.group(1)
        declared.add(name)

        q = re.match(r'(?:find|count|exists|delete)(?:First\d*|Top\d*)?(?:Distinct)?By(.+)', name)
        if not q:
            continue
        tail = q.group(1)
        tail = re.split(r'OrderBy', tail)[0]
        for prop in re.split(r'And|Or', tail):
            if not prop:
                continue
            for kw in KEYWORDS:
                prop = prop.replace(kw, '')
            if not prop:
                continue
            candidate = prop[0].lower() + prop[1:]
            # nested paths: findByLearnerIdIn -> learnerId
            if candidate in fields[entity]:
                continue
            # a trailing Id often means the field itself already ends in Id
            if candidate.rstrip('s') in fields[entity]:
                continue
            problems.append(
                f'{f.name}: {name}() derives from "{candidate}", '
                f'which {entity} does not have. Fields: {sorted(fields[entity])}')
    repo_methods[f.stem] = declared | INHERITED

# ---- calls made on repository fields ---------------------------------------
for f in list(root.rglob('*.java')):
    if '/repo/' in str(f):
        continue
    src = f.read_text()
    holders = dict(re.findall(r'private final (\w+Repository)\s+(\w+);', src))
    holders = {v: k for k, v in holders.items()}
    for var, repo in holders.items():
        if repo not in repo_methods:
            problems.append(f'{f.name}: unknown repository type {repo}')
            continue
        for call in set(re.findall(r'\b' + var + r'\.(\w+)\s*\(', src)):
            if call not in repo_methods[repo]:
                problems.append(f'{f.name}: {var}.{call}() is not declared on {repo}')

for p in sorted(set(problems)):
    print(p)
print('---')
print('problems:', len(set(problems)))
