#!/bin/bash
# Temporary probe: do the Java and the PowerShell lines match the kept ones?
mkdir -p probe-out
echo "== java"; java -version 2>&1 | head -1
bash android/ci/look-check.sh work/look
echo "== pwsh"; pwsh -NoProfile -Command '$PSVersionTable.PSVersion.ToString()'
cases=android/mirage/look
while read -r name w h; do
  pwsh -NoProfile -File windows/resources/termant-pc.ps1 look-answer $cases/$name.json $w $h $name.jpg phone > probe-out/$name.ps.txt 2> probe-out/$name.ps.err
  if cmp -s probe-out/$name.ps.txt $cases/$name.txt; then echo "PASS ps $name"; else echo "FAIL ps $name"; head -c 1500 probe-out/$name.ps.err; diff $cases/$name.txt probe-out/$name.ps.txt | head -n 30; fi
done < $cases/cases.txt
