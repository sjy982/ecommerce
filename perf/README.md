# 한정 수량 주문 부하 테스트 (JMeter)

재고 1,000개 상품에 주문 3,000건이 한꺼번에 몰리는 상황을 재현합니다. 주문 접수 속도와 정합성(초과 판매가 없는지)을 확인합니다. 개선 전 버전과도 같은 방법으로 비교할 수 있습니다.

처음 해 보는 사람도 따라 할 수 있게 순서대로 적었습니다. 각 단계에 **"이렇게 나오면 정상"**을 함께 적어 두었습니다.

---

## 0. 준비물 확인 (처음 한 번)

터미널에서 아래 명령을 하나씩 실행해 확인합니다.

| 확인 | 명령 | 이렇게 나오면 정상 |
|---|---|---|
| Docker Desktop이 켜져 있음 | `docker ps` | 표 머리글(`CONTAINER ID ...`)이 나옴. `Cannot connect` 에러면 Docker Desktop 앱을 켜고 고래 아이콘이 멈출 때까지 기다림 |
| JDK 17 | `/usr/libexec/java_home -v 17` | `/Library/Java/...` 같은 경로가 나옴 |
| JMeter 5.6.3 | `ls ~/apache-jmeter-5.6.3/bin/jmeter` | 경로가 그대로 출력됨. 다른 곳에 있으면 `export JMETER_HOME=설치경로` |
| python3 | `python3 --version` | `Python 3.x.x` |

로컬에 설치된 MySQL(3306)이나 Redis(6379)는 **건드리지 않습니다.** 테스트 전용 컨테이너를 MySQL **3307**, Redis **6380** 포트로 따로 띄웁니다.

---

## 1. 터미널 두 개 준비

| 터미널 | 역할 |
|---|---|
| **터미널 1** | 앱을 실행하고 로그를 계속 보여줌 (켜 둔 채로 둠) |
| **터미널 2** | 데이터 준비, 부하 실행, 결과 확인 |

두 터미널 모두 저장소의 `perf` 폴더로 이동합니다.

```bash
cd ~/ecommerce/perf
```

---

## 2. 터미널 1: 초기화하고 앱 실행

```bash
./reset.sh
```

컨테이너를 띄우고 DB와 Redis를 비웁니다. 처음에는 이미지 다운로드로 1~2분 걸릴 수 있습니다.

> 정상: 마지막 줄에 `초기화 완료: MySQL(3307) / Redis(6380)`

```bash
./run-app.sh
```

앱을 빌드하고 실행합니다. 이 터미널은 앱 로그가 계속 나오며 **켜 둔 채로 둡니다.**

> 정상: 로그에 `Started EcommerceApplication in 4.x seconds`

---

## 3. 터미널 2: 데이터 준비

```bash
./prepare.sh 1000 3000
```

다음을 차례로 합니다.
1. 스토어 가입
2. 카테고리와 상품 등록 (재고 1000)
3. **판매 오픈**: Redis에 재고 1000을 적재
4. 사용자 3000명 가입 후 로그인 토큰을 `out/tokens.csv`에 저장

20~30초 걸립니다.

> 정상:
> ```
> 판매 오픈: {"status":200,"message":"sale opened","data":{"productId":1,"stock":1000}}
> 사용자 3000명 토큰 생성 중...
> 준비 완료: productId=1, stock=1000, tokens=3000
> ```

---

## 4. 터미널 2: 예열 한 번 돌리기

앱을 막 띄운 직후에는 자바 JIT 컴파일 전이라 실제보다 느리게 나옵니다. **한 번 돌려서 예열만 하고, 이 결과는 버립니다.**

```bash
./run-load.sh 3000 1
./check.sh --wait
```

`check.sh --wait`는 결제가 다 끝날 때까지 약 1분 기다립니다. 예열 회차라 결과는 무시해도 됩니다.

---

## 5. 터미널 2: 진짜 측정

상품을 새로 만들고(새 재고 1000) 다시 측정합니다.

```bash
./prepare.sh 1000 3000
./run-load.sh 3000 1
```

`run-load.sh 3000 1`은 "요청 3000건을 1초에 걸쳐 시작"이라는 뜻입니다. 끝나면 요약이 나옵니다.

```
요청 3000건 (모두 1383ms 안에 전송 시작)
응답 코드: {'200': 1000, '409': 2000}
  접수 200   1000건  평균 1001ms  p50 1018  p95 1725  p99 1831  max 1917
  품절 409   2000건  평균 1332ms  p50 1287  p95 1846  p99 2013  max 2081
  에러          0건
마지막 접수 완료: 첫 요청부터 1919ms  → 주문 접수 521 TPS
전체 응답 완료: 2531ms  → 전체 처리량 1185 건/초

  [통과] 접수 건수 = 재고(1000)
  [통과] 에러(500·503·타임아웃) 0건
```

| 줄 | 의미 |
|---|---|
| `모두 N ms 안에 전송 시작` | JMeter가 3000건을 실제로 다 보내는 데 걸린 시간. 1000ms를 넘으면 측정 장비가 바빠서 1초 안에 못 보낸 것 |
| `200` / `409` | 주문 접수 / 품절 |
| `에러` | 500·503·타임아웃. **0이어야 정상** |
| `마지막 접수 완료` | 첫 요청부터 1000번째 접수 응답까지 걸린 시간 |
| `주문 접수 TPS` | 1000 ÷ 위 시간 |
| p50·p95·p99 | 응답 시간 분포. p95 = 100건 중 95번째로 빠른 응답 |

---

## 6. 터미널 2: 정합성 확인

```bash
./check.sh --wait
```

결제 1000건이 모두 끝날 때까지(약 1분) 기다린 뒤 DB와 Redis를 확인합니다.

> 정상:
> ```
> 결제 완료 대기: 57초
> 판매 총수량(product.stock) : 1000  (주문으로 바뀌지 않아야 함)
> 주문 수 / 판매 수량          : 1000 / 1000
> 결제 완료(PAID)              : 1000
> 남은 결제 job               : 0
> Redis 남은 재고              : 0
> [통과] 초과 판매 없음, Redis 재고 = 판매 총수량 − 판매 수량 (0)
> ```

여러 번 재서 평균을 보고 싶으면 **5~6단계만 반복**합니다(`prepare.sh` → `run-load.sh` → `check.sh --wait`). 같은 장비에서도 회차마다 10~20% 정도 차이가 납니다.

---

## 7. 그래프로 보기 (JMeter HTML 리포트)

```bash
open out/html-report/index.html
```

브라우저가 열립니다. 볼 만한 곳은 두 군데입니다.
- **Charts → Over Time → Response Times Over Time**: 시간에 따른 응답 시간
- **Charts → Throughput → Response Codes Per Second**: 초당 200·409 응답 수. 판매가 끝나는 순간 200이 0이 되고 409만 남는 게 보임

---

## 8. 개선 전 버전과 비교하기

Redis를 적용하기 전 버전(DB 비관적 락)을 **별도 폴더**에 꺼내 같은 방법으로 측정합니다. 지금 작업 중인 폴더는 건드리지 않습니다.

**① 개선 전 버전을 별도 폴더로 꺼내기 (처음 한 번)**

```bash
git -C ~/ecommerce worktree add ~/ecommerce-before refactor/payment-fast-path-sweeper
```

**② 터미널 1**: 실행 중인 앱을 `Ctrl+C`로 끈 뒤 아래를 실행합니다.

```bash
./reset.sh
APP_DIR="$HOME/ecommerce-before" ./run-app.sh
```

**③ 터미널 2**: 2~6단계와 똑같이 합니다. 예열 한 번 후 측정합니다.

```bash
./prepare.sh 1000 3000
./run-load.sh 3000 1
./check.sh --wait
```

개선 전 버전에는 판매 오픈 API가 없어서 `판매 오픈 API 없음(HTTP 404) → 개선 전 버전으로 보고 건너뜁니다`가 나옵니다. **정상입니다.** 이 버전은 품절을 404로 응답해서, 요약에도 `품절 404`로 표시됩니다.

**④ 다 쓰고 나면 꺼낸 폴더 정리**

```bash
git -C ~/ecommerce worktree remove ~/ecommerce-before
```

---

## 9. JMeter GUI로 보고 싶을 때

GUI는 계획을 눈으로 보거나 고칠 때 씁니다. **수치 측정은 5단계(non-GUI)로** 하는 게 JMeter 권장 방식입니다. GUI는 화면을 그리느라 CPU를 써서 결과가 느리게 나옵니다.

1. 터미널 2에서 `./prepare.sh 1000 3000`까지 합니다.
2. 같은 `perf` 폴더에서 GUI를 엽니다.
   ```bash
   ~/apache-jmeter-5.6.3/bin/jmeter -t order-load.jmx -JproductId=$(cat out/product_id)
   ```
3. 왼쪽 트리를 살펴봅니다.
   - **주문 요청 (Thread Group)**: 스레드 수 3000, Ramp-up 1초
   - **사용자 토큰**: `out/tokens.csv`를 한 줄씩 읽어 사용자마다 다른 토큰 사용
   - **POST /api/orders/payment**: 요청 본문
   - **200(접수) 또는 409(품절)면 정상**: 품절을 에러로 세지 않게 하는 검증
4. 상단 초록색 ▶ 버튼으로 실행합니다.
5. **요약 보고서**와 **집계 보고서**에서 결과를 봅니다. Error %에는 진짜 에러(500·503·타임아웃)만 잡힙니다.
6. 다시 돌리려면 터미널 2에서 `./prepare.sh`를 다시 하고, GUI를 새 상품 번호로 다시 엽니다.

"결과 트리"는 요청마다 응답을 저장해서 무거워 꺼 두었습니다. 요청 하나하나를 보고 싶을 때만 오른쪽 클릭 → Enable로 켭니다.

---

## 10. 조건 바꿔 보기

| 하고 싶은 것 | 명령 |
|---|---|
| 3000건을 3초에 나눠서 (초당 약 1000건) | `./run-load.sh 3000 3` |
| 재고 1만, 사용자 1만 5천 | `./prepare.sh 10000 15000` 후 `./run-load.sh 15000 5` |
| 커넥션 풀을 10으로 되돌려 비교 | 터미널 1에서 `JAVA_OPTS_EXTRA="-Dspring.datasource.hikari.maximum-pool-size=10" ./run-app.sh` |

---

## 11. 끝내기

1. 터미널 1에서 `Ctrl+C`로 앱을 끕니다.
2. 컨테이너를 정리합니다.
   ```bash
   docker compose -f ~/ecommerce/perf/docker-compose.yml stop    # 멈추기 (데이터 유지)
   docker compose -f ~/ecommerce/perf/docker-compose.yml down    # 삭제 (데이터도 삭제)
   ```

---

## 12. 막힐 때

| 증상 | 원인과 해결 |
|---|---|
| `Cannot connect to the Docker daemon` | Docker Desktop이 꺼져 있음. 앱을 켜고 다시 실행 |
| `port is already allocated` (3307·6380) | 같은 포트를 쓰는 컨테이너가 있음. `docker ps`로 찾아 `docker stop 이름` |
| `앱이 응답하지 않습니다` | 터미널 1의 앱이 아직 안 떴거나 죽음. 터미널 1 로그에서 `Started` 확인 |
| `Port 8080 was already in use` (터미널 1) | 다른 앱이 8080을 씀. `lsof -i :8080`으로 찾아서 종료 |
| `tokens=0` | 앱이 사용자 가입 요청을 못 받음. 터미널 1 로그 확인 후 `prepare.sh` 다시 |
| `JMeter 실행 실패` | `out/jmeter.out` 확인. JMeter 경로가 다르면 `export JMETER_HOME=경로` |
| 에러(500)가 생김 | 터미널 1 로그에서 `ERROR` 확인 |
| 회차마다 숫자가 다름 | 정상. 같은 맥에서 JMeter·앱·DB가 CPU를 나눠 써서 10~20% 흔들림. 여러 번 재서 범위로 기록 |

---

## 부록: 파일 설명

| 파일 | 하는 일 |
|---|---|
| `docker-compose.yml` | 테스트 전용 MySQL(3307)·Redis(6380) |
| `env.sh` | 앱 환경 변수(로컬 전용 더미 값)와 경로 |
| `reset.sh` | 컨테이너 기동, DB·Redis 비우기 |
| `run-app.sh` | 앱 실행 (SQL 디버그 로그 끔, `APP_DIR`·`JAVA_OPTS_EXTRA` 지원) |
| `prepare.sh` | 스토어·상품 등록, 판매 오픈, 사용자 토큰 생성 |
| `order-load.jmx` | JMeter 계획 |
| `run-load.sh` | JMeter non-GUI 실행 + 요약 + HTML 리포트 |
| `report.py` | 결과 요약 |
| `check.sh` | DB·Redis 정합성 확인 |

## 부록: 측정 기록

2026-11-13, MacBook 10코어 한 대에서 앱·MySQL·Redis·JMeter를 함께 실행했습니다. 재고 1,000 / 주문 3,000건(1초에 걸쳐 시작) / 예열 후 측정입니다. 모든 회차에서 초과 판매 0, 에러 0, 결제 1,000건 완료였습니다.

| 버전 | 1,000건 접수 완료 | 접수 TPS | 품절 응답 평균 |
|---|---|---|---|
| 개선 전: DB 비관적 락 (커넥션 10) | 6.2~7.8초 | 129~161 | 7.6~9.3초 |
| 개선 후: Redis 판정 (커넥션 10) | 2.36초 | 423 | 1.56초 |
| 개선 후: Redis 판정 (커넥션 30, 현재 설정) | 1.7~1.9초 | 521~626 | 1.0~1.3초 |
