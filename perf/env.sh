#!/bin/bash
# 부하 테스트용 앱 환경 변수. 로컬 전용 더미 값이다 (실서비스 비밀값 아님).
export DB_URL="jdbc:mysql://localhost:3307/ecommerce"
export DB_USERNAME=root
export DB_PASSWORD=root
export REDIS_HOST=localhost
export REDIS_PORT=6380
export GOOGLE_CLIENT_ID=perf-dummy
export GOOGLE_CLIENT_SECRET=perf-dummy
export JWT_SECRET_KEY=dGVzdC1sb25nLWxvbmctbG9uZy1sb25nLWxvbmctbG9uZy1zZWNyZXQta2V5
export JWT_RT_SECRET_KEY=dGVzdC1kaWZmZXJlbnQtbG9uZy1sb25nLWxvbmctbG9uZy1zZWNyZXQta2V5
export JWT_VALIDITY_MS=36000000
export JWT_RT_VALIDITY_MS=72000000
export JWT_TEMP_VALIDITY_MS=600000

PERF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PERF_DIR
export OUT_DIR="$PERF_DIR/out"
export JMETER_HOME="${JMETER_HOME:-$HOME/apache-jmeter-5.6.3}"
export APP_URL="${APP_URL:-http://localhost:8080}"
mkdir -p "$OUT_DIR"

mysql_exec() {   # 사용법: mysql_exec "SQL"
  docker exec -i ecom-perf-mysql mysql -uroot -proot -N ecommerce -e "$1" 2>/dev/null
}
