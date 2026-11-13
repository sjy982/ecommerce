#!/bin/bash
# 앱을 부하 테스트 설정으로 실행한다. 이 터미널은 앱 로그를 보여주며 계속 떠 있다 (종료: Ctrl+C).
# SQL 디버그 로그는 성능을 크게 떨어뜨리므로 끈다.
# 추가 JVM 옵션: JAVA_OPTS_EXTRA="-Dspring.datasource.hikari.maximum-pool-size=30" ./run-app.sh
set -euo pipefail
source "$(dirname "$0")/env.sh"
# APP_DIR: 실행할 앱 소스 폴더 (기본: 이 저장소). 개선 전 버전과 비교할 때 다른 폴더를 지정한다.
cd "${APP_DIR:-$PERF_DIR/..}"
./gradlew -q bootJar -x test
JAR=$(ls build/libs/*-SNAPSHOT.jar | head -1)
exec java -Xmx1g ${JAVA_OPTS_EXTRA:-} \
  -Dlogging.level.org.hibernate.SQL=warn \
  -Dlogging.level.org.hibernate.orm.jdbc.bind=warn \
  -Dlogging.level.org.hibernate.orm.jdbc.batch=warn \
  -Dlogging.level.org.hibernate.orm.jdbc.mutation=warn \
  -jar "$JAR"
