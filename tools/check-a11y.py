"""
A button with only an icon in it has no name. A screen reader announces "button" and
the person has no idea what it does.
"""
import pathlib, re

src = pathlib.Path('frontend/src')
problems = []
for f in src.rglob('*.jsx'):
    text = f.read_text()
    # a button whose whole body is an <Icon .../> and nothing else
    for m in re.finditer(r'<button([^>]*)>\s*(\{[^}]*\}\s*)?<Icon[^/]*/>\s*(\{[^}]*\}\s*)?</button>', text, re.S):
        attrs = m.group(1)
        trailing = (m.group(2) or '') + (m.group(3) or '')
        # a trailing expression holding a string literal is the button's name
        has_text = "'" in trailing or '"' in trailing
        if 'aria-label' not in attrs and 'title' not in attrs and not has_text:
            snippet = re.sub(r'\s+', ' ', m.group(0))[:70]
            problems.append(f'{f.name}: icon-only button with no name — {snippet}')

    # images need alt text, even decorative ones need an empty alt
    for m in re.finditer(r'<img(?![^>]*\balt=)[^>]*>', text):
        problems.append(f'{f.name}: <img> with no alt')

for p in sorted(set(problems)): print(p)
print('---')
print('problems:', len(set(problems)))
