#!/bin/sh
# Builds a release jar for every Minecraft version and loader and copies them to dist/, ready to
# upload to Modrinth (see MODRINTH.md). Pass version names to build only some: ./build-release.sh 26.3 26.3-neoforge
set -e
cd "$(dirname "$0")"
if [ "$#" -gt 0 ]; then
	versions="$*"
else
	versions=$(ls versions)
fi
tasks=""
for v in $versions; do
	tasks="$tasks :$v:build"
done
sh ./gradlew $tasks -x test --console=plain
mkdir -p dist
for v in $versions; do
	for jar in versions/"$v"/build/libs/diorama-*.jar; do
		case "$jar" in
			*-sources.jar|*-dev.jar|*-dev-shadow.jar) ;;
			*) cp "$jar" dist/ ;;
		esac
	done
done
ls -1 dist
