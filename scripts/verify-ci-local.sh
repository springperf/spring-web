#!/usr/bin/env bash
# Local parity check for the gates defined in .github/workflows/ci.yml (master).
#
# Why this exists: running "mvn test" with whatever JAVA_HOME is set is NOT the
# CI gate. The master javadoc job runs both the full doclint and the annotation
# -Werror gate on JDK 17, and the netty-leaks job runs on JDK 17 with paranoid
# leak detection. A local run with a different JDK or a truncated command
# sequence can pass while CI fails. Mirror the CI commands exactly instead of
# inventing a "close enough" local variant.
#
# Usage:
#   JDK17_HOME=/d/soft_space/Java/jdk-17.0.20+8 \
#     bash scripts/verify-ci-local.sh
#
# The script stops at the first failing gate.
set -euo pipefail

: "${JDK17_HOME:?set JDK17_HOME (JDK 17 used by the javadoc and netty-leaks jobs)}"

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"

run_with_jdk() {
  local home="$1"
  shift
  JAVA_HOME="$home" PATH="$home/bin:$PATH" "$@"
}

echo "==> docs: check-docs.sh"
bash scripts/check-docs.sh

echo "==> javadoc: reactor install (JDK 17)"
run_with_jdk "$JDK17_HOME" ./mvnw install -DskipTests -Dspotbugs.skip=true \
  -Dmodernizer.skip=true -Dmaven.javadoc.skip=true -Dmaven.source.skip=true \
  --batch-mode --no-transfer-progress -q

echo "==> javadoc: full doclint, all except missing (JDK 17)"
run_with_jdk "$JDK17_HOME" ./mvnw javadoc:javadoc \
  -DadditionalJOption=-Xdoclint:all,-missing \
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
