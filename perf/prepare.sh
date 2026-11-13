#!/bin/bash
# 앱이 떠 있는 상태에서 실행한다.
# 스토어 가입 → 카테고리 → 상품 등록(재고 STOCK) → 판매 오픈(Redis 적재) → 사용자 USERS명 토큰 생성
# 사용법: ./prepare.sh [STOCK=1000] [USERS=3000]
set -euo pipefail
source "$(dirname "$0")/env.sh"
STOCK="${1:-1000}"
USERS="${2:-3000}"
TS=$(date +%s)

curl -sf "$APP_URL/hc/env" >/dev/null || { echo "앱이 응답하지 않습니다. ./run-app.sh 먼저 실행하세요."; exit 1; }

STORE="perfstore$TS"   # 스토어 이름은 영문·숫자만 허용
STORE_TOKEN=$(curl -s -D - -o /dev/null -X POST "$APP_URL/api/store" -H 'Content-Type: application/json' \
  -d "{\"name\":\"$STORE\",\"password\":\"perf-pw\",\"phoneNumber\":\"010-1234-1234\"}" \
  | grep -i '^authorization:' | sed -E 's/^[Aa]uthorization: Bearer //' | tr -d '\r')
[ -n "$STORE_TOKEN" ] || { echo "스토어 가입 실패"; exit 1; }

mysql_exec "INSERT INTO category(name) VALUES ('perf-category-$TS');"
CATEGORY_ID=$(mysql_exec "SELECT id FROM category WHERE name='perf-category-$TS';")

curl -sf -o /dev/null -X POST "$APP_URL/api/product" -H "Authorization: Bearer $STORE_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"name\":\"limited-$TS\",\"price\":1000,\"stock\":$STOCK,\"description\":\"한정 수량\",\"categoryId\":$CATEGORY_ID}"
PRODUCT_ID=$(mysql_exec "SELECT id FROM product WHERE name='limited-$TS';")

OPEN_CODE=$(curl -s -o "$OUT_DIR/sale_open.json" -w '%{http_code}' -X POST "$APP_URL/api/product/$PRODUCT_ID/sale/open" -H "Authorization: Bearer $STORE_TOKEN")
if [ "$OPEN_CODE" = "200" ]; then
  echo "판매 오픈: $(cat "$OUT_DIR/sale_open.json")"
  echo "409" > "$OUT_DIR/sold_out_code"
else
  echo "판매 오픈 API 없음(HTTP $OPEN_CODE) → 개선 전 버전으로 보고 건너뜁니다. (이 버전은 품절을 404로 응답)"
  echo "404" > "$OUT_DIR/sold_out_code"
fi

echo "사용자 $USERS명 토큰 생성 중..."
seq 1 "$USERS" | xargs -P 20 -I{} sh -c \
  "curl -s -D - -o /dev/null -X POST '$APP_URL/api/users/join/test' | grep -i '^authorization:' | sed -E 's/^[Aa]uthorization: Bearer //' | tr -d '\r'" \
  > "$OUT_DIR/tokens.csv"

echo "$PRODUCT_ID" > "$OUT_DIR/product_id"
echo "$STOCK" > "$OUT_DIR/stock"
echo "준비 완료: productId=$PRODUCT_ID, stock=$STOCK, tokens=$(wc -l < "$OUT_DIR/tokens.csv" | tr -d ' ')"
