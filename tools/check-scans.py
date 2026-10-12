"""
Whole-collection reads in request paths.

`repo.findAll()` reads every document in a collection. At two hundred learners that is
invisible; at twenty thousand learners, with progress rows, logs and sessions growing
under them, one such call on a screen a thousand people open is the slowest thing on
the server, and nothing about it looks wrong in review. The scale passes replaced them
with indexed queries one file at a time, and the ones that fell between the file lists
are exactly the ones nobody went looking for. This looks for all of them.

A findAll (or a derived findAllByOrderBy..., which sorts but does not filter) is reported when it is made in a service or controller on a repository (or
MongoTemplate.findAll on a document) that is not on the allow-list below. The
allow-list is the small catalogue collections an admin types in by hand, which stay at
tens or hundreds of rows however many learners there are. Startup-only classes
(*Migration, *Backfill, *Seeder) are skipped: they run once, not per request.

The repository is identified by the receiver's declared type in the class, e.g.
`private final LearnerRepository learners;`. A paged findAll(Pageable) is not reported.
"""
import pathlib, re

root = pathlib.Path('backend/src/main/java/com/proitbridge/lms')

# Small, hand-entered collections. Bounded by what an admin types, not by learners.
ALLOW = {
    'BundleRepository',          # courses on sale
    'ModuleRepository',          # CourseModule: subjects
    'ChapterRepository',         # chapters in the catalogue
    'TopicRepository',           # one video each, catalogue sized
    'FeatureRepository',         # feature toggles
    'AppSettingRepository',      # settings
    'FaqRepository',             # FAQs
    'FormSectionRepository',     # intake form layout
    'FormFieldRepository',       # intake form layout
    'RubricRepository',          # marking rubrics
    'JobPostRepository',         # job board posts
    'CaseStudyRepository',       # case studies
    'CommunityLinkRepository',   # community links
    'ProjectRepository',         # Project: the project catalogue, not learner work
    'BatchRepository',           # cohorts: a few per month
    'SessionScheduleRepository', # recurring schedules
    'MentorCoverRepository',     # cover arrangements between mentors
    'VideoRepository',           # the video library
}
# Document classes MongoTemplate.findAll may read whole, matching the list above.
ALLOW_DOCS = {
    'Bundle', 'CourseModule', 'Chapter', 'Topic', 'Feature', 'AppSetting', 'Faq',
    'FormSection', 'FormField', 'Rubric', 'JobPost', 'CaseStudy', 'CommunityLink',
    'Project', 'Batch', 'SessionSchedule', 'MentorCover', 'Video',
}
SKIP_CLASS = re.compile(r'(Migration|Backfill|Seeder)$')


def strip(src):
    """Comments out, string contents blanked, line breaks kept so line numbers hold."""
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
            j = src.find('*/', i)
            j = n if j < 0 else j + 2
            out.append('\n' * src.count('\n', i, j)); i = j
        else:
            out.append(c); i += 1
    return ''.join(out)


problems = []
seen = 0
for f in sorted(list((root / 'service').rglob('*.java')) + list((root / 'web').rglob('*.java'))):
    if SKIP_CLASS.search(f.stem):
        continue
    src = strip(f.read_text())
    types = dict((name, typ) for typ, name in re.findall(
        r'(?:private|protected|public)?\s*(?:final\s+)?(\w+Repository|MongoTemplate)\s+(\w+)\s*[;=]', src))
    # findAll(), and the derived findAllByOrderBy...() forms, which sort without
    # filtering and so read just as much
    for m in re.finditer(r'(\w+)\s*\.\s*(findAll(?:ByOrderBy\w*)?)\s*\(([^()]*(?:\([^()]*\)[^()]*)*)\)', src):
        recv, meth, args = m.group(1), m.group(2), m.group(3).strip()
        line = src.count('\n', 0, m.start()) + 1
        typ = types.get(recv)
        if typ is None:
            continue
        seen += 1
        if typ == 'MongoTemplate':
            doc = re.match(r'(\w+)\.class', args)
            if doc and doc.group(1) in ALLOW_DOCS:
                continue
            problems.append(f'{f.relative_to(root)}:{line}: {recv}.findAll({args}) reads a whole collection')
            continue
        if typ in ALLOW:
            continue
        if re.search(r'Pageable|PageRequest|page', args):
            continue
        tail = src[m.end():m.end() + 12]
        how = meth + '()' + ('.stream()' if tail.lstrip().startswith('.stream') else '')
        problems.append(f'{f.relative_to(root)}:{line}: {recv}.{how} on {typ} reads a growing collection')

for p in problems: print(p)
print('---')
print('findAll calls on repositories:', seen)
print('problems:', len(problems))
