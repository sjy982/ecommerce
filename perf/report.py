"""JMeter 결과(.jtl)를 요약한다. 사용법: python3 report.py result.jtl STOCK [SOLD_OUT_CODE=409]
개선 전 버전은 품절을 404로 응답하므로 SOLD_OUT_CODE=404로 넘긴다."""
import csv
import sys


def pct(values, p):
    if not values:
        return 0
    values = sorted(values)
    return values[min(len(values) - 1, int(len(values) * p))]


def line(name, values):
    if not values:
        return f"  {name:<10} 0건"
    return (f"  {name:<10} {len(values):>5}건  평균 {sum(values) / len(values):>7.0f}ms  "
            f"p50 {pct(values, .5):>6}  p95 {pct(values, .95):>6}  p99 {pct(values, .99):>6}  max {max(values):>6}")


rows = list(csv.DictReader(open(sys.argv[1], encoding="utf-8")))
stock = int(sys.argv[2])
sold_out_code = sys.argv[3] if len(sys.argv) > 3 else "409"
t0 = min(int(r["timeStamp"]) for r in rows)
sent_within = max(int(r["timeStamp"]) for r in rows) - t0

by_code = {}
for r in rows:
    by_code.setdefault(r["responseCode"], []).append(r)
accepted = by_code.get("200", [])
sold_out = by_code.get(sold_out_code, [])
errors = [r for r in rows if r["responseCode"] not in ("200", sold_out_code)]

accept_done_ms = max((int(r["timeStamp"]) + int(r["elapsed"]) - t0 for r in accepted), default=0)
accept_tps = len(accepted) / (accept_done_ms / 1000) if accept_done_ms else 0
all_done_ms = max(int(r["timeStamp"]) + int(r["elapsed"]) - t0 for r in rows)

print(f"요청 {len(rows)}건 (모두 {sent_within}ms 안에 전송 시작)")
print(f"응답 코드: { {k: len(v) for k, v in sorted(by_code.items())} }")
print(line("접수 200", [int(r["elapsed"]) for r in accepted]))
print(line(f"품절 {sold_out_code}", [int(r["elapsed"]) for r in sold_out]))
print(line("에러", [int(r["elapsed"]) for r in errors]))
print(f"마지막 접수 완료: 첫 요청부터 {accept_done_ms}ms  → 주문 접수 {accept_tps:.0f} TPS")
print(f"전체 응답 완료: {all_done_ms}ms  → 전체 처리량 {len(rows) / (all_done_ms / 1000):.0f} 건/초")
print()
checks = [
    (f"접수 건수 = 재고({stock})", len(accepted) == stock),
    ("에러(500·503·타임아웃) 0건", not errors),
]
for name, ok in checks:
    print(f"  [{'통과' if ok else '미달'}] {name}")
