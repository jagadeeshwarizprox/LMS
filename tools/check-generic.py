"""
Curriculum assumptions in an LMS that does not know what it teaches.

Copy that names a subject, a tool or a language is a guess about somebody else's course.
It belongs in the data an admin enters, not in the product. Comments are exempt: they
explain the code to whoever reads it next, and a concrete example there is useful.
"""
import pathlib, re

roots = [pathlib.Path('backend/src/main/java/com/proitbridge/lms'),
         pathlib.Path('frontend/src')]

SUBJECTS = ['Python', 'pandas', 'numpy', 'matplotlib', 'Statistics', 'Power BI',
            'Tableau', 'Excel', 'SQL', 'Machine Learning', 'Deep Learning', 'NLP']

# where a subject legitimately appears: the browser runtime really is Python, and the
# checker itself names them
EXEMPT = {
    # the browser runtime really is Python, and the code runner names the language
    # it runs on purpose
    'PythonRunner.jsx', 'Materials.jsx', 'DocViewer.jsx', 'ChapterDetail.jsx',
    'check-generic.py',
    # an upload whitelist is a list of file extensions, so it necessarily names
    # formats and languages. None of it is copy: nothing here reaches a screen.
    'FileService.java',
}


def strings_only(src, jsx):
    """Only what a person can read on screen: string literals and JSX text."""
    out = []
    for m in re.finditer(r'"((?:[^"\\]|\\.)*)"', src):
        out.append((m.start(), m.group(1)))
    if jsx:
        for m in re.finditer(r'\'((?:[^\'\\]|\\.)*)\'', src):
            out.append((m.start(), m.group(1)))
        for m in re.finditer(r'>([^<>{}\n]{4,})<', src):
            out.append((m.start(), m.group(1)))
    return out


def in_comment(src, pos):
    line_start = src.rfind('\n', 0, pos) + 1
    line = src[line_start:pos]
    if '//' in line or line.strip().startswith('*'):
        return True
    before = src[:pos]
    return before.count('/*') > before.count('*/')


problems = []
for root in roots:
    for f in root.rglob('*'):
        if f.suffix not in ('.java', '.jsx', '.js') or f.name in EXEMPT:
            continue
        src = f.read_text()
        for pos, text in strings_only(src, f.suffix != '.java'):
            if in_comment(src, pos):
                continue
            for subject in SUBJECTS:
                if re.search(r'\b' + re.escape(subject) + r'\b', text, re.I):
                    snippet = text.strip()[:64]
                    problems.append(f'{f.name}: names "{subject}" on screen — "{snippet}"')

for p in sorted(set(problems)): print(p)
print('---')
print('problems:', len(set(problems)))
