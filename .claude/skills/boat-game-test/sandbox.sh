#!/bin/bash
# Runs on a boat.dev VM, started by boat_run.py: installs what's missing, copies the pushed tree
# from ~/src over ~/diorama (keeping its Gradle caches), runs one node's client game tests with
# software rendering, and packs the results into ~/result.tgz. Its output goes to ~/sandbox.log and
# its exit code to ~/run-exit, which boat_run.py polls for.
# usage: sandbox.sh <node> <tests|all> [KEY=VALUE...]
set -u
# One run per VM: a start the API reported as failed may have started after all.
exec 9>~/sandbox.lock
flock -n 9 || exit 75
rm -f ~/run-exit
touch ~/run-started
exec > ~/sandbox.log 2>&1
trap 'echo $? > ~/run-exit' EXIT
node=$1
tests=$2
shift 2

if ! dpkg -s xvfb xauth libgl1-mesa-dri mesa-vulkan-drivers rsync >/dev/null 2>&1; then
	echo "installing packages"
	sudo DEBIAN_FRONTEND=noninteractive apt-get update -qq
	sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq xvfb xauth libgl1-mesa-dri libglx-mesa0 libegl1 \
		mesa-vulkan-drivers rsync libxrandr2 libxi6 libxcursor1 libxxf86vm1 libxtst6 libxkbcommon-x11-0 \
		libasound2t64 libpulse0 libdecor-0-0 >/dev/null || exit 1
fi
if [ ! -x ~/jdk25/bin/java ]; then
	echo "installing JDK 25"
	curl -sSfL https://cdn.azul.com/zulu/bin/zulu25.36.205-ca-jdk25.0.4.1-linux_x64.tar.gz | tar xz -C ~ || exit 1
	mv ~/zulu25.36.205-ca-jdk25.0.4.1-linux_x64 ~/jdk25
fi

mkdir -p ~/diorama
rsync -a --delete --exclude .git --exclude .gradle --exclude build --exclude run --exclude logs ~/src/ ~/diorama/ || exit 1
cd ~/diorama || exit 1

export JAVA_HOME=~/jdk25 GRADLE_OPTS=-Dorg.gradle.daemon=false
# Gradle's own heap is set in gradle.properties; this caps the game at 40% of the VM's memory.
export JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=40
# Mesa's CPU renderer, OpenGL as on Dara's PC. Minecraft 26.x asks SDL for an sRGB window, which
# Xvfb's GLX visuals lack and EGL has; without this it falls back to Vulkan, which is no faster here.
# Versions without SDL ignore the setting.
export LIBGL_ALWAYS_SOFTWARE=1 SDL_VIDEO_FORCE_EGL=1
if [ "$tests" != all ]; then
	export DIORAMA_TESTS=$tests
fi
for pair in "$@"; do
	export "$pair"
done

run=versions/$node/build/run/clientGameTest
rm -rf "$run/screenshots" "$run/crash-reports" "$run/logs" ~/result ~/result.tgz
start=$(date +%s)
echo "running :$node:runClientGameTest (tests: $tests)"
# 90 min at most; boat_run.py passes TEST_SECONDS, what its own deadline leaves after setup.
timeout --kill-after=60 "${TEST_SECONDS:-5400}" xvfb-run -a -s '-screen 0 1920x1080x24' bash ./gradlew --no-daemon --console=plain ":$node:runClientGameTest" > ~/gradle.log 2>&1
code=$?
minutes=$(( ($(date +%s) - start) / 60 ))

# Any failure packing the results fails the script, so the run isn't reported without its evidence.
mkdir -p ~/result || exit 1
cp ~/gradle.log ~/result/ || exit 1
for part in screenshots crash-reports logs/latest.log; do
	if [ -e "$run/$part" ]; then
		cp -r "$run/$part" ~/result/ || exit 1
	fi
done
echo "{\"exit\": $code, \"minutes\": $minutes}" > ~/result/result.json || exit 1
tar czf ~/result.tgz -C ~/result . || exit 1
echo "exit=$code after $minutes min"
