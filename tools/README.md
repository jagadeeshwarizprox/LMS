# Offline checks

These were written because the build machine that produced this code had no access to
Maven Central, so `mvn` could never run. They are not a substitute for a compile. Run
`mvn clean package` first; use these when you want a fast answer without a full build.

| Script | What it catches |
|---|---|
| `ParseCheck.java` | Real syntax errors, using javac's own parser with no classpath |
| `check-derived-queries.py` | A Spring Data method name deriving from a property the document does not have. This fails the whole application at startup, not just that call |
| `check-accessors.py` | An accessor called on a document that the document does not declare, scoped per method so shadowed variable names are not reported |
| `check-service-calls.py` | A call to a service method that does not exist, or with the wrong number of arguments |
| `check-generic.py` | Copy naming a subject, tool or language on screen. An LMS does not know what it teaches; that belongs in the data an admin enters |
| `check-no-sample-data.py` | Anything writing content on startup other than the bootstrap, invented addresses in code, and config defaults that point at a real-looking external URL |
| `check-wiring.py` | Every frontend call against every backend route, both ways. Finds a call to a path that does not exist, and a route nothing calls, which is worse because it looks finished |
| `check-edits.py` | A field the frontend reads that no backend `put()` sends. Catches an edit that silently matched nothing: both sides compile, the feature is just absent |
| `check-scope.py` | A method touching a field its own class does not declare. What a bad de-Lombok pass leaves behind: it parses cleanly and fails at compile |
| `check-java-imports.py` | A class of ours used but never imported. javac rejects it, but an IDE compiler will write the class file anyway and let it fail at startup as `NoClassDefFoundError` with no package on the name |
| `check-generic.py` | Copy naming a subject, tool or language on screen. An LMS does not know what it teaches; that belongs in the data an admin enters |
| `check-no-sample-data.py` | Anything writing content on startup other than the bootstrap, invented addresses in code, and config defaults that point at a real-looking external URL |
| `check-wiring.py` | Every frontend call against every backend route, both ways. Finds a call to a path that does not exist, and a route nothing calls, which is worse because it looks finished |
| `check-edits.py` | A field the frontend reads that no backend `put()` sends. Catches an edit that silently matched nothing: both sides compile, the feature is just absent |
| `check-scope.py` | A method touching a field its own class does not declare. What a bad de-Lombok pass leaves behind: it parses cleanly and fails at compile |
| `check-java-imports.py` | A type used but never imported, which will not compile, and imports nothing uses. Pass `--all` to list the second kind |
| `check-constructors.py` | A final field never assigned, or a parameter accepted and dropped. What a careless constructor edit leaves behind |
| `check-routes.py` | Two handlers mapped to one path, which makes Spring refuse to start |
| `check-icons.py` | An icon name that is not in the map, which renders a blank button and never warns |
| `check-a11y.py` | An icon-only button with no accessible name, and images with no alt |
| `check-imports.py` | A frontend helper used but never imported. Vite builds this cleanly and it throws when the branch renders, which is the worst kind of failure |

| `offline-compile/verify.sh` | A real symbol-resolving `javac` over the whole backend, with the third-party API surface stubbed. Catches what the structural scripts cannot see: a missing accessor, a constructor that gained an argument, a local declared twice after a rename. It cannot tell you a stub has drifted from the real jar, so it is a fast loop before `mvn`, not a replacement for it |

```bash
./tools/offline-compile/verify.sh
javac tools/ParseCheck.java -d /tmp/pc && java -cp /tmp/pc ParseCheck backend/src/main/java
python3 tools/check-derived-queries.py
python3 tools/check-accessors.py
python3 tools/check-service-calls.py
```

Each one prints its findings and a count. All four report zero as of this build.

## Extra checks run against this build

Not shipped as scripts, but run once and clean:

| Check | Result |
|---|---|
| `Map.of` even arguments and the ten pair limit | 0 |
| Types imported from our own packages exist | 0 |
| Enum constants exist on the enum being used | 0 |
| No duplicate method signatures in a class | 0 |
| Interface implementations declare every method | 0 |
| Every `@Value` key exists in `application.yml` | 0 |
| Every third-party import has a `pom.xml` dependency | 0 |
| Every relative frontend import resolves | 0 |
| Duplicate route mappings across 137 routes | 0 |

## The three-level refactor

`Topic` used to mean a subject and `Chapter` meant a lesson that carried its video and
its assessment together. There are now three levels: `CourseModule` (subject),
`Chapter` (the sitting that is assessed once), `Topic` (one video and its files).

The rename was mechanical and the checkers caught what it broke, which is what they are
for. Worth knowing if you run them after a similar pass:

- `check-derived-queries` found two repositories deriving from a field that had moved.
- `check-accessors` found a chapter still being asked for a duration it no longer has,
  and a loop variable shadowing an outer one of a different type, which is a compile
  error rather than a style problem.
- `check-routes` found two handlers on one path after new endpoints were added beside
  older ones.
- `check-generic` is exempt-listed by filename, so a renamed file silently loses its
  exemption. `ChapterEditor.jsx` became `ChapterDetail.jsx` and the list needed updating.

A blanket textual rename also introduced a local variable shadowing a repository field
of the same name, which none of the structural scripts catch. `offline-compile/verify.sh`
does, because it is the compiler; `mvn clean package` remains the only thing that also
checks the stubs were right.

## Name derived logins

Login IDs and first passwords are made from the person's name rather than generated. The
checkers that mattered while building it:

- `check-constructors` caught every service that gained `CredentialService` but not the
  matching constructor argument. Spring would have failed at startup rather than at
  compile, which is a slower loop.
- `check-derived-queries` confirmed `findByLoginIdIgnoreCase` resolves against a real
  field, which is the class of mistake that only surfaces on the first request.
- `check-wiring` caught `POST /super/people/{}/reset-password` sitting there with nothing
  calling it, which is worse than missing because it reads as finished.

None of them can tell you the scheme itself is a bad idea. That judgement is in the
README under Signing in, and it rests on the forced change and the expiry rather than on
the password being hard to guess.
