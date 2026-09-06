#!/usr/bin/env bash
#
# A real symbol-resolving compile of the backend without Maven Central.
#
# The checkers beside this directory are structural: they read the source as text and
# reason about it. This does not. It runs javac over every backend source with the
# third-party API surface replaced by hand written stubs, so the compiler resolves
# every symbol in our own code for real. That is the class of mistake the README says
# only `mvn clean package` catches: a missing accessor, a constructor that gained an
# argument, a local variable declared twice after a rename.
#
# What it cannot tell you: whether a stub matches the real library. The stubs cover
# Spring Boot 3.3.4, Spring Security 6, Spring Data MongoDB, jjwt 0.12.6, POI 5.3,
# jakarta.servlet 6, Jackson and SLF4J as this project uses them. A signature that
# drifts from the real jar shows up on the first `mvn clean package` and nowhere here.
# Run both. This one takes about four seconds.
#
set -euo pipefail
cd "$(dirname "$0")/../.."

OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

find tools/offline-compile/stubs -name '*.java' > "$OUT/stubs.txt"
javac --release 17 -nowarn -d "$OUT/stubs" @"$OUT/stubs.txt"

find backend/src/main/java -name '*.java' > "$OUT/src.txt"
javac --release 17 -nowarn -proc:none -cp "$OUT/stubs" -d "$OUT/classes" \
      -Xmaxerrs 400 @"$OUT/src.txt"

echo "---"
echo "sources compiled: $(wc -l < "$OUT/src.txt")"
echo "classes written:  $(find "$OUT/classes" -name '*.class' | wc -l | tr -d ' ')"
