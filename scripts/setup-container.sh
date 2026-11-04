#!/usr/bin/env bash
# One-time setup for a fresh cloud container (safe to run more than once):
#   1. installs JDK 25 via apt (Minecraft 26.2 and Fabric Loom need it)
#   2. points Gradle's daemon at it in ~/.gradle/gradle.properties (user level, not the repo)
#   3. installs ~/.gradle/init.d/central-mirror.gradle, which redirects repo.maven.apache.org
#      (HTTP 429 from the shared sandbox IP) to repo1.maven.org
# Nothing under ~/.gradle is part of the repo.
set -euo pipefail

SUDO=""
if [ "$(id -u)" -ne 0 ]; then
  if command -v sudo >/dev/null 2>&1; then SUDO="sudo"; else echo "need root or sudo to install packages" >&2; exit 1; fi
fi

# 1. JDK 25
find_jdk() { ls -d /usr/lib/jvm/java-25-openjdk-* 2>/dev/null | grep -v '\.jinfo$' | head -n1 || true; }
JDK="$(find_jdk)"
if [ -z "$JDK" ] || [ ! -x "$JDK/bin/javac" ]; then
  echo "Installing openjdk-25-jdk-headless ..."
  $SUDO apt-get update -qq
  $SUDO apt-get install -y -qq openjdk-25-jdk-headless
  JDK="$(find_jdk)"
fi
[ -x "$JDK/bin/javac" ] || { echo "JDK 25 not found after install" >&2; exit 1; }
echo "JDK 25: $JDK"

# 2. gradle.properties: keep other lines, keep exactly one org.gradle.java.home line
mkdir -p "$HOME/.gradle/init.d"
PROPS="$HOME/.gradle/gradle.properties"
touch "$PROPS"
TMP="$(mktemp)"
grep -v '^org\.gradle\.java\.home=' "$PROPS" > "$TMP" || true
echo "org.gradle.java.home=$JDK" >> "$TMP"
cat "$TMP" > "$PROPS"
rm -f "$TMP"
echo "Wrote $PROPS"

# 3. Maven Central mirror init script (overwritten each run, so it stays current)
cat > "$HOME/.gradle/init.d/central-mirror.gradle" <<'GRADLE'
// Sandbox workaround: repo.maven.apache.org answers HTTP 429 from this shared IP; repo1.maven.org serves the same artifacts.
def fix = { repo ->
    if (repo instanceof MavenArtifactRepository && repo.url.toString().startsWith('https://repo.maven.apache.org/maven2')) {
        repo.url = 'https://repo1.maven.org/maven2/'
    }
}
beforeSettings { it.pluginManagement.repositories.configureEach(fix); it.dependencyResolutionManagement.repositories.configureEach(fix) }
allprojects { buildscript.repositories.configureEach(fix); repositories.configureEach(fix) }
GRADLE
echo "Wrote $HOME/.gradle/init.d/central-mirror.gradle"
echo "Done. Next: ./gradlew build   (if a 429 still appears, just rerun)"
