#!/bin/bash
# 컨테이너를 띄우고 DB·Redis를 비운다. 앱을 띄우기 "전에" 실행한다 (테이블은 앱 기동 시 생성됨).
set -euo pipefail
source "$(dirname "$0")/env.sh"
docker compose -f "$PERF_DIR/docker-compose.yml" up -d
echo "MySQL 준비 대기..."
for _ in $(seq 1 60); do
  docker exec ecom-perf-mysql mysqladmin -uroot -proot ping 2>/dev/null | grep -q alive && break
  sleep 2
done
docker exec ecom-perf-mysql mysql -uroot -proot -e "DROP DATABASE IF EXISTS ecommerce; CREATE DATABASE ecommerce;" 2>/dev/null
docker exec ecom-perf-redis redis-cli FLUSHALL >/dev/null   # 부하 테스트 전용 컨테이너만 비움
rm -rf "$OUT_DIR"; mkdir -p "$OUT_DIR"
echo "초기화 완료: MySQL(3307) / Redis(6380)"
