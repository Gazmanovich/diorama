#!/bin/bash
# A real dedicated server for one node, on a boat.dev VM: the loader's own server install (Fabric's
# launcher, NeoForge's or Forge's installer), with the node's release jar in its mods folder or
# without it. The game tests run it through RealServer.java; sandbox.sh builds the jar first when a
# run passes DIORAMA_REAL_SERVER=1. Installs are kept in ~/real-servers/<node> for the next run.
#
# usage: real_server.sh start <node> with|without   start it, and wait until it's up
#        real_server.sh cmd <node> <command...>     a console command
#        real_server.sh stop <node>                 stop it and wait
#        real_server.sh vanilla <node> <name> <seconds> the vanilla client joins, stays, leaves
#        real_server.sh vanilla-start <node> <name>     the vanilla client joins and stays, for the console to move about
#        real_server.sh vanilla-stop <node> <name>      it leaves
# Prints the port on start. The world, the logs and the server's console are in ~/real-servers/<node>/.
# REAL_SERVER_JAR: a Diorama jar to start "with" instead of the checkout's, such as an older release.
# REAL_SERVER_DIORAMA_PROPERTIES: written to the server's config/diorama-server.properties on start.
# REAL_SERVER_KIND=paper: a Paper server of the node's Minecraft version instead of the loader's, for
# what a server plugin does to a Diorama client. Only "without": Paper loads no mods.
# REAL_SERVER_PLUGINS: download addresses of plugin jars for its plugins folder, with spaces between.
set -u
action=$1
node=$2
shift 2
repo=~/diorama
root=~/real-servers/$node
port=25590
mkdir -p "$root"

prop() {
	local value
	# The checkout may have Windows line ends.
	value=$(grep -h "^$1=" "$repo/versions/$node/gradle.properties" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '\r')
	[ -n "$value" ] || value=$(grep -h "^$1=" "$repo/gradle.properties" | head -1 | cut -d= -f2- | tr -d '\r')
	echo "$value"
}
mc=$(prop minecraft_version)
case $node in
	*-neoforge) loader=neoforge ;;
	*-forge) loader=forge ;;
	*) loader=fabric ;;
esac
[ "${REAL_SERVER_KIND:-}" = paper ] && loader=paper

# The Java its Minecraft version was made for: Forge 1.20.1 won't start on a newer one.
java_home() {
	local want
	case $mc in
		1.20.*) want=17 ;;
		1.21.*) want=21 ;;
		*) want=25 ;;
	esac
	if [ "$want" = 25 ]; then
		echo ~/jdk25
		return
	fi
	# Gradle's toolchains on the warm snapshot have them already.
	local gradle_jdk
	gradle_jdk=$(ls -d ~/.gradle/jdks/eclipse_adoptium-$want-amd64-linux* 2>/dev/null | head -1)
	if [ -n "$gradle_jdk" ] && [ -x "$gradle_jdk/bin/java" ]; then
		echo "$gradle_jdk"
		return
	fi
	if [ ! -x ~/jdk$want/bin/java ]; then
		mkdir -p ~/jdk$want.part
		curl -sSfL "https://api.adoptium.net/v3/binary/latest/$want/ga/linux/x64/jdk/hotspot/normal/eclipse" | tar xz -C ~/jdk$want.part --strip-components 1 || exit 1
		mv ~/jdk$want.part ~/jdk$want
	fi
	echo ~/jdk$want
}

# What's installed, so a warm VM installs again after a loader or Minecraft version change.
installed_as() {
	echo "$loader $mc $(prop loader_version) $(prop fabric_api_version) $(prop neoforge_version) $(prop forge_version)"
}

install() {
	[ "$(cat "$root/installed" 2>/dev/null)" = "$(installed_as)" ] && return 0
	# Not trusted again until this install finishes.
	rm -f "$root/installed"
	rm -rf "$root/libraries" "$root/libs" "$root/server.jar" "$root/installer.jar" "$root/run.sh" "$root/user_jvm_args.txt"
	local java
	java=$(java_home)/bin/java
	case $loader in
		fabric)
			curl -sSfL "https://meta.fabricmc.net/v2/versions/loader/$mc/$(prop loader_version)/1.1.0/server/jar" -o "$root/server.jar" || return 1
			local api
			api=$(prop fabric_api_version)
			mkdir -p "$root/libs"
			curl -sSfL "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$api/fabric-api-$api.jar" -o "$root/libs/fabric-api.jar" || return 1
			;;
		neoforge)
			local v
			v=$(prop neoforge_version)
			curl -sSfL "https://maven.neoforged.net/releases/net/neoforged/neoforge/$v/neoforge-$v-installer.jar" -o "$root/installer.jar" || return 1
			(cd "$root" && "$java" -jar installer.jar --install-server "$root" > install.log 2>&1) || return 1
			;;
		paper)
			local build
			# Paper's download service turns away clients that don't name themselves.
			build=$(curl -sSfL -A 'game-test-server-install' "https://fill.papermc.io/v3/projects/paper/versions/$mc/builds/latest" 				| python3 -c 'import json, sys; print(json.load(sys.stdin)["downloads"]["server:default"]["url"])') || return 1
			curl -sSfL -A 'game-test-server-install' "$build" -o "$root/server.jar" || return 1
			;;
		forge)
			local v="$mc-$(prop forge_version)"
			# Forge's maven refuses clients that don't look like a browser or Gradle.
			curl -sSfL -A 'Gradle/8.14' "https://maven.minecraftforge.net/net/minecraftforge/forge/$v/forge-$v-installer.jar" -o "$root/installer.jar" || return 1
			(cd "$root" && "$java" -jar installer.jar --installServer "$root" > install.log 2>&1) || return 1
			;;
	esac
	installed_as > "$root/installed"
}

running() {
	[ -e "$root/pid" ] && kill -0 "$(cat "$root/pid")" 2>/dev/null
}

start() {
	local with=$1
	running && stop
	install || { echo "installing the $loader server for $mc failed" >&2; exit 1; }
	local java
	java=$(java_home)/bin/java
	mkdir -p "$root/mods"
	rm -f "$root"/mods/*.jar
	if [ "$loader" = fabric ]; then
		cp "$root/libs/fabric-api.jar" "$root/mods/"
	fi
	if [ "$loader" = paper ]; then
		[ "$with" = without ] || { echo "a Paper server loads no mods: start it without" >&2; exit 1; }
		# Emptied with its plugins' own folders, so each run starts from the plugins' defaults.
		rm -rf "$root/plugins"
		mkdir -p "$root/plugins" "$root/plugin-jars"
		local address
		for address in ${REAL_SERVER_PLUGINS:-}; do
			local file="$root/plugin-jars/$(basename "$address")"
			[ -s "$file" ] || curl -sSfL -A 'game-test-server-install' "$address" -o "$file" || { rm -f "$file"; echo "downloading $address failed" >&2; exit 1; }
			cp "$file" "$root/plugins/"
		done
	fi
	if [ "$with" = with ]; then
		local jar=${REAL_SERVER_JAR:-}
		# The only one: sandbox.sh clears the folder before it builds.
		[ -n "$jar" ] || jar=$(ls "$repo/versions/$node/build/libs/"diorama-*.jar 2>/dev/null | grep -v -e '-sources' -e '-dev' -e '-gametest' -e '-gameshim' | head -1)
		[ -n "$jar" ] && [ -f "$jar" ] || { echo "no release jar in versions/$node/build/libs (or REAL_SERVER_JAR): build it with :$node:assemble" >&2; exit 1; }
		cp "$jar" "$root/mods/"
	fi
	echo "eula=true" > "$root/eula.txt"
	if [ -n "${REAL_SERVER_DIORAMA_PROPERTIES:-}" ]; then
		mkdir -p "$root/config"
		printf '%b\n' "$REAL_SERVER_DIORAMA_PROPERTIES" > "$root/config/diorama-server.properties"
	fi
	# Offline, so the test client's made-up name gets in. Bound to this machine only. No watchdog:
	# on a VM rendering the client in software the server can stall past its limit. A fixed seed,
	# so runs see the same land.
	if [ ! -e "$root/server.properties.base" ]; then
		cat > "$root/server.properties.base" <<-EOF
			online-mode=false
			server-ip=127.0.0.1
			server-port=$port
			max-tick-time=-1
			level-seed=diorama-real-server
			gamemode=creative
			spawn-protection=0
			allow-flight=true
			view-distance=8
			simulation-distance=6
			white-list=false
		EOF
	fi
	cp "$root/server.properties.base" "$root/server.properties"
	[ -n "${REAL_SERVER_PROPERTIES:-}" ] && printf '%b\n' "$REAL_SERVER_PROPERTIES" >> "$root/server.properties"
	: > "$root/stdin"
	local console="$root/console-$(date +%H%M%S)-$with.log"
	ln -sf "$console" "$root/console.log"
	(
		cd "$root" || exit 1
		case $loader in
			fabric | paper) exec "$java" -Xmx1536M -jar server.jar nogui ;;
			neoforge) exec "$java" -Xmx1536M @libraries/net/neoforged/neoforge/$(prop neoforge_version)/unix_args.txt nogui ;;
			forge) exec "$java" -Xmx1536M @libraries/net/minecraftforge/forge/$mc-$(prop forge_version)/unix_args.txt nogui ;;
		esac
	# Nothing of the server's may hold this script's output open: whoever runs it reads to the end.
	) < <(exec tail -f "$root/stdin" 2> /dev/null) > "$console" 2>&1 &
	echo $! > "$root/pid"
	for _ in $(seq 1 600); do
		if grep -q 'Done (' "$console"; then
			echo "$port"
			return 0
		fi
		if ! running; then
			echo "the server stopped while starting; see $console" >&2
			tail -40 "$console" >&2
			exit 1
		fi
		sleep 1
	done
	echo "the server didn't start in 10 minutes; see $console" >&2
	exit 1
}

cmd() {
	echo "$*" >> "$root/stdin"
}

stop() {
	# A test that failed before its vanilla player left leaves the helper waiting: it goes too.
	for helper in "$root"/vanilla-*.pid; do
		[ -e "$helper" ] || continue
		# Only if that process is still the helper: a reused VM can give its number to another.
		if grep -q vanilla_client.py "/proc/$(cat "$helper")/cmdline" 2>/dev/null; then
			kill "$(cat "$helper")" 2>/dev/null
		fi
		rm -f "$helper"
	done
	running || return 0
	cmd stop
	for _ in $(seq 1 120); do
		if ! running; then
			rm -f "$root/pid"
			pkill -f "^tail -f $root/stdin"
			return 0
		fi
		sleep 1
	done
	kill "$(cat "$root/pid")" 2>/dev/null
	sleep 5
	kill -9 "$(cat "$root/pid")" 2>/dev/null
	rm -f "$root/pid"
	pkill -f "^tail -f $root/stdin"
}

# A player without Diorama: the vanilla client, no mods at all (vanilla_client.py). Exits 0 once it
# has joined and stayed for the given seconds without being disconnected.
vanilla() {
	local name=$1 seconds=$2
	python3 "$(dirname "$0")/vanilla_client.py" "$mc" "$(java_home)/bin/java" "$name" "127.0.0.1:$port" "$root/console.log" "$seconds"
}

# The same, staying until vanilla-stop: returns once it has joined.
vanilla_start() {
	local name=$1
	local log="$root/vanilla-$name.log"
	# Emptied first: an earlier run's log on a reused VM would say she joined already.
	: > "$log"
	nohup python3 "$(dirname "$0")/vanilla_client.py" "$mc" "$(java_home)/bin/java" "$name" "127.0.0.1:$port" "$root/console.log" 36000 > "$log" 2>&1 &
	echo $! > "$root/vanilla-$name.pid"
	for _ in $(seq 1 900); do
		if grep -q "joined after" "$log"; then
			cat "$log"
			return 0
		fi
		if ! kill -0 "$(cat "$root/vanilla-$name.pid")" 2>/dev/null; then
			cat "$log"
			exit 1
		fi
		sleep 1
	done
	cat "$log"
	exit 1
}

vanilla_stop() {
	local pid
	pid=$(cat "$root/vanilla-$1.pid" 2>/dev/null) || return 0
	rm -f "$root/vanilla-$1.pid"
	grep -q vanilla_client.py "/proc/$pid/cmdline" 2>/dev/null || return 0
	kill "$pid" 2>/dev/null
	for _ in $(seq 1 60); do
		kill -0 "$pid" 2>/dev/null || return 0
		sleep 1
	done
	kill -9 "$pid" 2>/dev/null
}

case $action in
	start) start "$1" ;;
	cmd) cmd "$@" ;;
	stop) stop ;;
	vanilla) vanilla "$1" "$2" ;;
	vanilla-start) vanilla_start "$1" ;;
	vanilla-stop) vanilla_stop "$1" ;;
	*) echo "unknown action $action" >&2; exit 2 ;;
esac
