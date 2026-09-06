"""
An icon name that is not in the map renders nothing, which is a blank button. Vite
never complains, so this does.
"""
import pathlib, re

src = pathlib.Path('frontend/src')
icon_file = (src / 'components' / 'Icon.jsx').read_text()
block = icon_file.split('const MAP = {')[1].split('}')[0]
known = set(re.findall(r'(\w+):', block))

problems = []
for f in src.rglob('*.jsx'):
    text = f.read_text()
    for name in re.findall(r'<Icon\s+name="([\w-]+)"', text):
        if name not in known: problems.append(f'{f.name}: <Icon name="{name}"> is not in the map')
    for name in re.findall(r"icon=[\"']([\w-]+)[\"']", text):
        if name not in known: problems.append(f'{f.name}: icon="{name}" is not in the map')
    # nav tables and option lists carry names as bare strings in tuples
    for name in re.findall(r"',\s*'([\w-]+)'\]", text):
        if name in known or name.isupper(): continue
        if re.search(r"\['/[\w/-]+',\s*'[^']+',\s*'" + name + r"'\]", text):
            problems.append(f'{f.name}: nav icon "{name}" is not in the map')

for p in sorted(set(problems)): print(p)
print('---')
print('problems:', len(set(problems)))
