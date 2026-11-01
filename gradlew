#!/usr/bin/env bash
set -euo pipefail

# Lightweight, low-memory Gradle build wrapper for ShyCubes.
# Uses native javac + jar against the server's Paper libraries so CI/startup builds
# finish in <1s without spawning a heavy Gradle daemon alongside the running JVM.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION="1.0.0"
ARTIFACT_NAME="shycubes-${VERSION}.jar"

if [[ "${USE_SYSTEM_GRADLE:-false}" == "true" ]] && command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi

for arg in "$@"; do
  if [[ "$arg" == "clean" ]]; then
    rm -rf "${ROOT_DIR}/build"
  fi
done

SRC_DIR="${ROOT_DIR}/src/main/java"
RES_DIR="${ROOT_DIR}/src/main/resources"
CLASSES_DIR="${ROOT_DIR}/build/classes/java/main"
RESOURCES_OUT="${ROOT_DIR}/build/resources/main"
LIBS_OUT="${ROOT_DIR}/build/libs"

rm -rf "${CLASSES_DIR}" "${RESOURCES_OUT}"
mkdir -p "${CLASSES_DIR}" "${RESOURCES_OUT}" "${LIBS_OUT}"

# Assemble compile classpath from Paper server libraries (newest jars first)
SERVER_LIBS_DIR="${DEPLOY_SERVER_ROOT:-/home/opc/mc}/libraries"
if [[ ! -d "${SERVER_LIBS_DIR}" ]]; then
  echo "ERROR: Paper server libraries directory not found at ${SERVER_LIBS_DIR}" >&2
  exit 1
fi

CLASSPATH="$(find "${SERVER_LIBS_DIR}" -type f -name "*.jar" | sort -rV | paste -sd: -)"

mapfile -t JAVA_SOURCES < <(find "${SRC_DIR}" -type f -name "*.java" | sort)
if [[ "${#JAVA_SOURCES[@]}" -eq 0 ]]; then
  echo "ERROR: No Java sources found in ${SRC_DIR}" >&2
  exit 1
fi

echo "Compiling ${#JAVA_SOURCES[@]} Java source files -> ${CLASSES_DIR}"
javac -J-Xmx192m \
  -encoding UTF-8 \
  --release 21 \
  -cp "${CLASSPATH}" \
  -d "${CLASSES_DIR}" \
  "${JAVA_SOURCES[@]}"

# Process resources and expand ${version} in plugin.yml
if [[ -d "${RES_DIR}" ]]; then
  cp -R "${RES_DIR}/." "${RESOURCES_OUT}/"
  if [[ -f "${RESOURCES_OUT}/plugin.yml" ]]; then
    sed "s/\${version}/${VERSION}/g" "${RES_DIR}/plugin.yml" > "${RESOURCES_OUT}/plugin.yml"
  fi
fi

# Merge resources into classes directory and package JAR
cp -R "${RESOURCES_OUT}/." "${CLASSES_DIR}/"
rm -f "${LIBS_OUT}/${ARTIFACT_NAME}"
jar --create --file "${LIBS_OUT}/${ARTIFACT_NAME}" -C "${CLASSES_DIR}" .

echo "BUILD SUCCESSFUL: ${LIBS_OUT}/${ARTIFACT_NAME}"
