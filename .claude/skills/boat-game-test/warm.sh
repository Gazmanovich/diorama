#!/bin/bash
# Runs on the VM that becomes the snapshot, started by boat_snapshot.py after sandbox.sh has set the
# VM up. Compiles every node and fetches what each node's runClientGameTest depends on (Minecraft
# jars, mappings, libraries, assets) without starting the game. Then boots each node once
# (DIORAMA_TESTS=boot), which fetches the rest (the game's own natives only resolve when it runs),
# and records the files that run opens in ~/prefetch-lists/<node>.list, which boat_run.py reads
# ahead on a new VM. Clears this run's leftovers at the end so VMs start clean.
# Its output goes to ~/warm.out and its exit code to ~/run-exit, as with sandbox.sh.
# usage: warm.sh <commit>
set -u
exec 9>~/sandbox.lock
flock -n 9 || exit 75
rm -f ~/run-exit
touch ~/run-started
exec > ~/warm.out 2>&1
trap 'echo $? > ~/run-exit' EXIT
commit=$1
cd ~/diorama || exit 1
export JAVA_HOME=~/jdk25 GRADLE_OPTS=-Dorg.gradle.daemon=false
cat > ~/skip-run.gradle <<'EOF'
allprojects { tasks.matching { it.name == 'runClientGameTest' }.configureEach { enabled = false } }
EOF
nodes=$(ls versions)
tasks=$(for n in $nodes; do echo ":$n:runClientGameTest"; done)

start=$(date +%s)
# Two workers: more NeoForge decompiles at once run a 16 GB VM out of memory.
bash ./gradlew --no-daemon --console=plain --continue --max-workers=2 -I ~/skip-run.gradle compileJava $tasks > ~/warm.log 2>&1
code=$?
echo "warm-up: exit=$code after $(( $(date +%s) - start )) s"
if [ $code != 0 ]; then
	grep -E "FAILED|What went wrong" -A3 ~/warm.log | head -40
	exit 1
fi

command -v strace >/dev/null || sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq strace >/dev/null || exit 1
export JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=40 LIBGL_ALWAYS_SOFTWARE=1 SDL_VIDEO_FORCE_EGL=1 DIORAMA_TESTS=boot
rm -rf ~/prefetch-lists
mkdir -p ~/prefetch-lists
failed=""
for n in $nodes; do
	s=$(date +%s)
	# --seccomp-bpf stops only on the traced calls, so the game runs at close to full speed.
	timeout --kill-after=60 900 strace -f -qq --seccomp-bpf -e trace=openat -o /tmp/trace xvfb-run -a -s '-screen 0 1920x1080x24' \
		bash ./gradlew --no-daemon --console=plain ":$n:runClientGameTest" > ~/boot-$n.log 2>&1
	code=$?
	grep -oE '"/[^"]+"' /tmp/trace | tr -d '"' | grep -vE '^/(proc|sys|dev|tmp|run)/|/build/run/' | sort -u \
		| while read -r f; do [ -f "$f" ] && echo "$f"; done > ~/prefetch-lists/$n.list
	rm -f /tmp/trace
	echo "$n boot: exit=$code after $(( $(date +%s) - s )) s, $(wc -l < ~/prefetch-lists/$n.list) files to read ahead"
	[ $code = 0 ] || failed="$failed $n"
done
# Every node has to boot from this cache for the snapshot to be worth saving.
if [ -n "$failed" ]; then
	echo "these nodes failed their boot run, see ~/boot-<node>.log on the VM:$failed"
	for n in $failed; do grep -E "FAILED|What went wrong|Exception" -A3 ~/boot-$n.log | head -12; done
	exit 1
fi

rm -rf ~/src ~/result ~/result.tgz ~/gradle.log ~/sandbox.log ~/skip-run.gradle versions/*/build/run/clientGameTest/{saves,screenshots,logs,crash-reports}
echo "Warmed on master $commit, $(date -u +%Y-%m-%dT%H:%MZ), all $(echo $nodes | wc -w) nodes." > ~/diorama-snapshot.txt
