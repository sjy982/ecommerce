#!/bin/bash
# DB·Redis 정합성 확인. --wait 를 주면 결제가 모두 끝날 때까지(최대 3분) 기다린 뒤 확인한다.
set -euo pipefail
source "$(dirname "$0")/env.sh"
PRODUCT_ID=$(cat "$OUT_DIR/product_id")
STOCK=$(cat "$OUT_DIR/stock")
if [ "${1:-}" = "--wait" ]; then
  START=$(date +%s)
  for _ in $(seq 1 180); do
    LEFT=$(mysql_exec "SELECT COUNT(*) FROM orders WHERE product_id=$PRODUCT_ID AND status='PENDING_PAYMENT';")
    [ "$LEFT" = "0" ] && break
    sleep 1
  done
  echo "결제 완료 대기: $(( $(date +%s) - START ))초"
fi
ORDERS=$(mysql_exec "SELECT COUNT(*) FROM orders WHERE product_id=$PRODUCT_ID;")
SOLD=$(mysql_exec "SELECT COALESCE(SUM(quantity),0) FROM orders WHERE product_id=$PRODUCT_ID AND status<>'PAYMENT_FAILED';")
PAID=$(mysql_exec "SELECT COUNT(*) FROM orders WHERE product_id=$PRODUCT_ID AND status='PAID';")
DB_STOCK=$(mysql_exec "SELECT stock FROM product WHERE id=$PRODUCT_ID;")
JOBS=$(mysql_exec "SELECT COUNT(*) FROM payment_job;")
REDIS=$(docker exec ecom-perf-redis redis-cli GET "stock:product:$PRODUCT_ID")   # 개선 전 버전이면 비어 있음
echo "판매 총수량(product.stock) : $DB_STOCK  (주문으로 바뀌지 않아야 함)"
echo "주문 수 / 판매 수량          : $ORDERS / $SOLD"
echo "결제 완료(PAID)              : $PAID"
echo "남은 결제 job               : $JOBS"
echo "Redis 남은 재고              : $REDIS"
EXPECT=$(( STOCK - SOLD ))
if [ -z "$REDIS" ]; then
  # 개선 전 버전: Redis를 쓰지 않고 DB product.stock을 직접 차감한다
  if [ "$SOLD" -le "$STOCK" ] && [ "$DB_STOCK" = "$EXPECT" ]; then
    echo "[통과] (개선 전 버전) 초과 판매 없음, DB 재고 = 판매 총수량 − 판매 수량 ($EXPECT)"
  else
    echo "[확인 필요] (개선 전 버전) 판매 수량 $SOLD / 재고 $STOCK / DB 재고 $DB_STOCK (기대 $EXPECT)"
  fi
elif [ "$SOLD" -le "$STOCK" ] && [ "$REDIS" = "$EXPECT" ]; then
  echo "[통과] 초과 판매 없음, Redis 재고 = 판매 총수량 − 판매 수량 ($EXPECT)"
else
  echo "[확인 필요] 판매 수량 $SOLD / 재고 $STOCK / Redis $REDIS (기대 $EXPECT)"
fi
