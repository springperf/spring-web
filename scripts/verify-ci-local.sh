#!/usr/bin/env bash
# Local parity check for the gates defined in .github/workflows/ci.yml (2.7.x).
#
# Why this exists: running "mvn test" with whatever JAVA_HOME is set is NOT the CI
# gate. The 2.7.x CI javadoc job uses JDK 8 for the full doclint and JDK 17 for
# the annotation -Werror gate; the netty-leaks job uses JDK 17. Local runs with a
# different JDK and a truncated command sequence previously passed while CI
# failed twice (invalid -Werror on JDK 8; GC timing hiding ByteBuf leaks).
#
# Usage:
#   JDK8_HOME=/d/soft_space/Java/jdk8u502-b07 \
#   JDK17_HOME=/d/soft_space/Java/jdk-17.0.20+8 \
#   bash scripts/verify-ci-local.sh
#
# The script stops at the first failing gate. It intentionally mirrors the CI
# commands rather than inventing a "close enough" local variant.
set -euo pipefail

: "${JDK8_HOME:?set JDK8_HOME (JDK 8 used by the 2.7.x build)}"
: "${JDK17_HOME:?set JDK17_HOME (JDK 17 used by the netty-leaks job)}"

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"

run_with_jdk() {
  local home="$1"
  shift
  JAVA_HOME="$home" PATH="$home/bin:$PATH" "$@"
}

echo "==> docs: check-docs.sh"
bash scripts/check-docs.sh

echo "==> javadoc: reactor install (JDK 8)"
run_with_jdk "$JDK8_HOME" ./mvnw install -DskipTests -Dspotbugs.skip=true \
  -Dmodernizer.skip=true -Dmaven.javadoc.skip=true -Dmaven.source.skip=true \
  --batch-mode --no-transfer-progress -q

echo "==> javadoc: full doclint, all except missing (JDK 8)"
run_with_jdk "$JDK8_HOME" ./mvnw javadoc:javadoc -Ddoclint=all,-missing \
  --batch-mode --no-transfer-progress -q

echo "==> javadoc: annotation package, missing tags are errors (JDK 17)"
run_with_jdk "$JDK17_HOME" ./mvnw -q -pl spring-web dependency:build-classpath \
  -Dmdep.outputFile=target/cp.txt

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) sep=';' ;;
  *) sep=':' ;;
esac
run_with_jdk "$JDK17_HOME" "$JDK17_HOME/bin/javadoc" \
  -Xdoclint:all -Werror -encoding UTF-8 -docencoding UTF-8 -charset UTF-8 \
  -d target/jd-annotation \
  -classpath "$(cat spring-web/target/cp.txt)${sep}${repo_root}/spring-web/target/classes" \
  -sourcepath spring-web/src/main/java io.springperf.web.annotation

echo "==> netty-leaks: paranoid, CI module list (JDK 17)"
run_with_jdk "$JDK17_HOME" bash scripts/check-netty-leaks.sh --run --online --clean 1 \
  -pl spring-web,spring-web-test,spring-web-support-test -am

echo "[OK] all ci.yml gates reproduced locally"
