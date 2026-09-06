"""
Sample data, placeholder defaults and invented addresses in the running code.

The rule: nothing writes content without a request, and no default is a value that
looks real. A plausible wrong URL is worse than a blank one, because nothing flags it
and the first learner gets the dead link.
"""
import pathlib, re

back = pathlib.Path('backend/src/main/java/com/proitbridge/lms')
cfg = pathlib.Path('backend/src/main/resources/application.yml')

problems = []


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


# 1. anything that writes on startup other than the bootstrap
for f in back.rglob('*.java'):
    src = f.read_text()
    if re.search(r'ApplicationRunner|CommandLineRunner|@PostConstruct', src) \
       and f.stem != 'BootstrapSeeder':
        problems.append(f'{f.name}: writes on startup and is not the bootstrap')

# 2. invented people and addresses in code, ignoring comments and javadoc
for f in back.rglob('*.java'):
    body = f.read_text()
    for m in re.finditer(r'"([^"]*@(?:example\.com|test\.com)[^"]*)"', body):
        problems.append(f'{f.name}: invented address {m.group(1)}')
    for m in re.finditer(r'"(demo[-_][^"]*)"', body):
        problems.append(f'{f.name}: placeholder value "{m.group(1)}"')

# 3. a config default that is a URL or an address rather than blank
for i, line in enumerate(cfg.read_text().split('\n'), 1):
    if line.strip().startswith('#'):
        continue
    m = re.search(r'\$\{[A-Z_]+:([^}]+)\}', line)
    if not m:
        continue
    default = m.group(1).strip()
    # localhost is a development default, not a pretend value. What matters is a
    # default pointing at a real-looking external address that nobody will notice
    # is wrong until a learner clicks it.
    if re.match(r'https?://(?!localhost|127\.0\.0\.1)', default):
        problems.append(f'application.yml:{i}: default is an external URL — {default}')
    if re.search(r'@(example|test)\.', default):
        problems.append(f'application.yml:{i}: invented address — {default}')

for p in sorted(set(problems)): print(p)
print('---')
print('problems:', len(set(problems)))
