#!/bin/bash
# JMeter를 non-GUI로 실행하고 결과를 분석한다. prepare.sh 다음에 실행한다.
# 사용법: ./run-load.sh [THREADS=3000] [RAMP_SECONDS=1]
set -euo pipefail
source "$(dirname "$0")/env.sh"
THREADS="${1:-3000}"
RAMP="${2:-1}"
PRODUCT_ID=$(cat "$OUT_DIR/product_id")
STOCK=$(cat "$OUT_DIR/stock")
rm -rf "$OUT_DIR/result.jtl" "$OUT_DIR/html-report"

"$JMETER_HOME/bin/jmeter" -n -t "$PERF_DIR/order-load.jmx" \
  -Jthreads="$THREADS" -Jramp="$RAMP" -JproductId="$PRODUCT_ID" -Jtokens="$OUT_DIR/tokens.csv" \
  -l "$OUT_DIR/result.jtl" -j "$OUT_DIR/jmeter.log" -e -o "$OUT_DIR/html-report" > "$OUT_DIR/jmeter.out" 2>&1 \
  || { echo "JMeter 실행 실패: $OUT_DIR/jmeter.out 확인"; exit 1; }

python3 "$PERF_DIR/report.py" "$OUT_DIR/result.jtl" "$STOCK" "$(cat "$OUT_DIR/sold_out_code" 2>/dev/null || echo 409)"
echo "JMeter HTML 리포트: $OUT_DIR/html-report/index.html"
