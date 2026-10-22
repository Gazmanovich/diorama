#!/bin/sh
# Builds a release jar for every Minecraft version and loader and copies them to dist/. Pass version
# names to build only some: ./build-release.sh 26.3 26.3-neoforge. Set MOD_VERSION to build under
# another version number than gradle.properties has.
set -e
cd "$(dirname "$0")"
if [ "$#" -gt 0 ]; then
	versions="$*"
else
	versions=$(ls versions)
fi
mod_version=${MOD_VERSION:-$(sed -n 's/^mod_version=//p' gradle.properties)}
tasks=""
for v in $versions; do
	tasks="$tasks :$v:build"
done
sh ./gradlew $tasks -Pmod_version="$mod_version" -x test --console=plain
# Only this build's jars: build/libs keeps those of earlier builds under other version numbers.
mkdir -p dist
rm -f dist/*.jar
for v in $versions; do
	for jar in versions/"$v"/build/libs/diorama-"$mod_version"+*.jar; do
		case "$jar" in
			*-sources.jar|*-dev.jar|*-dev-shadow.jar) ;;
			*) cp "$jar" dist/ ;;
		esac
	done
done
ls -1 dist
