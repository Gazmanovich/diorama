#!/bin/bash
# Runs on the VM that becomes the snapshot, started by boat_snapshot.py after sandbox.sh has set the
# VM up: compiles every node and fetches everything each node's runClientGameTest needs (Minecraft
# jars, mappings, libraries, assets) without starting the game, checks that a second pass needs no
# network, then clears this run's leftovers so VMs start clean.
# Its output goes to ~/warm.out and its exit code to ~/run-exit, as with sandbox.sh.
# usage: warm.sh <commit>
set -u
exec 9>~/sandbox.lock
flock -n 9 || exit 75
touch ~/run-started
exec > ~/warm.out 2>&1
trap 'echo $? > ~/run-exit' EXIT
commit=$1
cd ~/diorama || exit 1
export JAVA_HOME=~/jdk25 GRADLE_OPTS=-Dorg.gradle.daemon=false
cat > ~/skip-run.gradle <<'EOF'
allprojects { tasks.matching { it.name == 'runClientGameTest' }.configureEach { enabled = false } }
EOF
tasks=$(for n in $(ls versions); do echo ":$n:runClientGameTest"; done)

start=$(date +%s)
# Two workers: more NeoForge decompiles at once run a 16 GB VM out of memory.
bash ./gradlew --no-daemon --console=plain --continue --max-workers=2 -I ~/skip-run.gradle compileJava $tasks > ~/warm.log 2>&1
code=$?
echo "warm-up: exit=$code after $(( $(date +%s) - start )) s"
if [ $code != 0 ]; then
	grep -E "FAILED|What went wrong" -A3 ~/warm.log | head -40
	exit 1
fi
# Anything still missing would make this pass fail.
bash ./gradlew --no-daemon --console=plain --offline -I ~/skip-run.gradle $tasks > ~/warm-offline.log 2>&1 || {
	echo "the offline pass failed: something is still downloaded at run time"
	grep -E "What went wrong" -A5 ~/warm-offline.log | head -40
	exit 1
}
echo "offline pass: everything is cached"

rm -rf ~/src ~/result ~/result.tgz ~/gradle.log ~/sandbox.log ~/skip-run.gradle versions/*/build/run/clientGameTest/{saves,screenshots,logs,crash-reports}
echo "Warmed on master $commit, $(date -u +%Y-%m-%dT%H:%MZ), all $(ls versions | wc -l) nodes." > ~/diorama-snapshot.txt
