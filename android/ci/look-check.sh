#!/usr/bin/env bash
# Checks the lines that `phone look` makes of Mirage's answers: for each answer kept in
# android/mirage/look/<name>.json they must be exactly those in <name>.txt. The same files check
# `pc look` on Windows, so both say the same about the same picture.
#   look-check.sh <work dir>        needs a JDK (javac, java)
# Look.java uses only org.json of Android; here it runs with the same org.json as Android has it
# (open-json, Apache 2.0), fetched at a fixed revision.
set -uo pipefail
work="$1"; mkdir -p "$work/json/org/json" "$work/classes"
root="$(cd "$(dirname "$0")" && git rev-parse --show-toplevel)"
cases="$root/android/mirage/look"
for f in JSON JSONArray JSONException JSONObject JSONString JSONStringer JSONTokener; do
  [ -s "$work/json/org/json/$f.java" ] || curl -sSL --fail --retry 3 -o "$work/json/org/json/$f.java" \
    "https://raw.githubusercontent.com/tdunning/open-json/2027333b18b8be4c78da91e056a3d9ee721bc995/src/main/java/org/json/$f.java" || exit 1
done
javac -nowarn -encoding UTF-8 -d "$work/classes" "$work"/json/org/json/*.java \
  "$root/android/src/com/termant/app/Look.java" "$root/android/ci/LookTest.java" || { echo "FAIL Look.java does not compile"; exit 1; }
failed=0
while read -r name width height; do
  [ -n "$name" ] || continue
  java -Dfile.encoding=UTF-8 -cp "$work/classes" com.termant.app.LookTest "$cases/$name.json" "$width" "$height" "$name.jpg" > "$work/$name.txt" 2> "$work/$name.err"
  if [ $? = 0 ] && cmp -s "$work/$name.txt" "$cases/$name.txt"; then echo "PASS $name"
  else echo "FAIL $name"; cat "$work/$name.err"; diff "$cases/$name.txt" "$work/$name.txt" | head -n 20; failed=1; fi
done < "$cases/cases.txt"
[ "$failed" = 0 ] && echo "PASS phone look says of every kept answer what it should"
exit $failed
