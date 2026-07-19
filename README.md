# stock-batch-service

주식 모의투자 서비스의 배치/워커 서버입니다.

## 역할

- 외부 주식 API에서 필요한 종목 시세 수집
- Redis 최신가 캐시 갱신
- 가격 변경 이벤트 발행
- 주문장 미체결 주문 체결 조건 검사
- 시뮬레이션일별 평가금액, 수익률, 랭킹 정산

## 현재 API

- `GET /internal/stock-batch/v1/system/status`
- `POST /internal/stock-batch/v1/jobs/market-data/refresh` (호환 모드 전용. 기본 post-close coordinator가 켜져 있으면 실행하지 않고 `SKIPPED`)
- `POST /internal/stock-batch/v1/jobs/order-book-execution/run`
- `POST /internal/stock-batch/v1/jobs/auto-participant-cash-flow/run`
- `GET /internal/stock-batch/v1/jobs/auto-participant-cash-flow/status`
- `PATCH /internal/stock-batch/v1/jobs/auto-participant-cash-flow/status`
- `GET /internal/stock-batch/v1/jobs/runtime-controls`
- `PATCH /internal/stock-batch/v1/jobs/runtime-controls/{jobName}`
- `POST /internal/stock-batch/v1/jobs/auto-market/run`
- `POST /internal/stock-batch/v1/jobs/auto-market-profile-queue/reconcile` (기본 coordinator 모드에서는 정규장·주문장 OPEN 상태의 bounded 큐 복구만 허용하고, PRE_OPEN 직접 호출은 `SKIPPED`)
- `POST /internal/stock-batch/v1/jobs/auto-market-order-expiry/run`
- `POST /internal/stock-batch/v1/jobs/listing-auto-market/run`
- `POST /internal/stock-batch/v1/jobs/portfolio-settlement/run`
- `POST /internal/stock-batch/v1/jobs/market-close/rollover`
- `POST /internal/stock-batch/v1/jobs/corporate-actions/run` (호환 모드 전용. 기본 post-close coordinator가 켜져 있으면 실행하지 않고 `SKIPPED`)

## 현재 잡

- `MarketDataRefreshScheduler`: 관심/보유/미체결 종목 가격 갱신, Redis 최신가/채널 발행
- `AutoParticipantCashFlowScheduler`: 자동 참여자 주기 입금을 `stock_account_cash_flow`와 `stock_account.cash_balance`에 반영
- `AutoMarketScheduler`: 자동 참여자 주문 생성, 자동장 미체결 주문 만료, 상장주관사 주문 공급 job을 실행
- `MarketPriceProvider`: 실제 시세 provider 교체 지점. 현재 기본값은 `stock.batch.market-data.provider=mock`
- `KisMarketPriceProvider`: `stock.batch.market-data.provider=kis`일 때 KIS OpenAPI 국내주식 현재가 시세를 호출
- `OrderBookExecutionScheduler`: `market_type=ORDER_BOOK` 사용자 매수/매도 주문을 가격 우선, 시간 우선으로 매칭
- `InternalOrderBookExecutionService`: 주문장 체결 엔진
  - 지정가끼리는 교차된 호가를 체결합니다.
  - 시장가 매수는 최우선 지정가 매도 호가와, 시장가 매도는 최우선 지정가 매수 호가와 체결합니다.
  - 양쪽이 모두 시장가이면 가격 기준이 없으므로 체결하지 않고 다음 스캔을 기다립니다.
- `PortfolioSettlementScheduler`: 기본 EOD의 지연 민감 prefix. 10초마다 소형 세션·cycle 제어행을 확인하고 18시 시장 차단·원장 동결과 `settlement_eligible_at` 이후 포트폴리오 정산까지 실행
- `PostCloseCoordinatorScheduler`: `PORTFOLIO_SETTLED` 이후의 기본 EOD suffix. 00시 이후 현금·기업행사·보고서, PRE_OPEN 가격·자동시장 준비와 readiness를 거래일 cycle 기준으로 한 phase씩 실행
- `MarketCloseRolloverService`: 장마감 종가를 다음 장 가격제한폭 기준가로 넘기기 위해 `stock_price.current_price`를 `previous_close`로 복사
- `HoldingCleanupScheduler`: 체결 hot path에서 즉시 삭제하지 않은 0주/0예약 보유 row를 보존 기간 이후 별도 유지보수 job으로 정리
- `BatchMetadataRetentionJob`: 기본 비활성인 POST_CLOSE 경량 유지보수. 오래된 완료 JobInstance를 compact archive로 옮기고, 별도 승인된 job name만 선택적으로 purge

## 실행과 검증

아래 명령은 `zeroq-common` 루트에서 실행합니다.

```bash
./gradlew :stock-batch-service:bootRun
./gradlew :stock-batch-service:bootRun --args='--spring.profiles.active=local'
./gradlew :stock-batch-service:bootRun --args='--spring.profiles.active=local-direct'
./gradlew :stock-batch-service:compileJava
./gradlew :stock-batch-service:test
./gradlew :stock-batch-service:mysqlTest
scripts/stock-smoke.sh
STOCK_SMOKE_RUN_BATCH_JOBS=true scripts/stock-smoke.sh
STOCK_BATCH_INTERNAL_TOKEN=<token> STOCK_SMOKE_RUN_BATCH_JOBS=true scripts/stock-smoke.sh
ZEROQ_GATEWAY_SHARED_SECRET=<secret> STOCK_BATCH_INTERNAL_TOKEN=<token> STOCK_SMOKE_RUN_GATEWAY_BATCH_JOBS=true scripts/stock-smoke.sh
scripts/stock-gateway-h2-smoke.sh
```

`mysqlTest`는 MySQL 8 Testcontainers에서 실제 운영 조인 형태의 `FOR SHARE OF f` session fence drain과 전역 제어행 비잠금, 취소·정정의 소유 주문 조회가 fence만 잠그고 주문·계좌·시장설정·시계 행을 미리 잠그지 않는지, 정확한 주문 PK 잠금의 gap-lock 비회귀, 기존 `(market_type, status, symbol)` 보조 인덱스의 확장 PK를 사용한 열린 주문 `id` keyset·filesort 비회귀, signal `SKIP LOCKED` 선두 건너뛰기, canonical DDL과 운영 EOD ALTER 11개의 재실행 멱등성, 구형 스키마의 누락 컬럼·제약·인덱스 생성 및 기존 signal/스냅샷/체결 손익 요약 backfill, 적용 전후 hot-ledger DDL 비변경을 검증하는 격리 테스트입니다. 일반 `test`와 `check`에는 Docker 기동 비용을 넣지 않습니다. 반면 명시적으로 실행한 `mysqlTest`는 Docker를 사용할 수 없으면 컨테이너 초기화에서 실패하여 MySQL 검증이 실행되지 않은 상태를 성공으로 숨기지 않습니다. 승인 증거에는 결과 XML의 `tests/skipped/failures/errors`를 함께 기록하며, 1건이라도 skipped이면 실제 MySQL 동시성·주문/체결 부하 검증을 통과한 것으로 판정하지 않습니다.

일반 `scripts/stock-smoke.sh`는 기본 종목을 가정하지 않습니다. `STOCK_SMOKE_EXPECT_SEEDED_MARKET=true` 또는 `STOCK_SMOKE_PLACE_ORDER=true`로 종목 기반 검증을 켤 때는 `STOCK_SMOKE_SYMBOL`을 명시해야 합니다. H2 smoke는 별도 smoke data를 넣기 때문에 wrapper에서 symbol을 명시합니다.

## 포트

| Profile | Port |
|---|---:|
| `local` | `20481` |
| `local-direct` | `20481` |
| `dev` | `20481` |
| `prod` | `10481` |
| `test` | `30481` |

## Local Direct / Gateway 전환

- 기본 활성 profile은 `local-direct`입니다.
- `local-direct`는 `local` DB/Redis 설정을 재사용하면서 Eureka 등록/탐색을 끕니다.
- batch는 stock-back을 HTTP로 호출하지 않고 같은 DB/Redis 원장을 기준으로 처리하므로 direct 모드에서 별도 service URL 전환은 없습니다.
- Gateway/Eureka 경유로 되돌리려면 `local` profile을 사용합니다.

## 내부 의존성

- `web-common-core`

## 데이터베이스 / Redis

- DB schema: `STOCK_SERVICE`
- MySQL business DDL (canonical): `../stock-back-service/src/main/resources/db/ddl/stock_all.sql`
- H2 test DDL: `src/main/resources/db/ddl/stock_h2.sql`
- Batch metadata schema: `STOCK_BATCH_METADATA`
- Batch metadata DDL:
  - MySQL: `src/main/resources/db/schema/batch-metadata-mysql.sql`
  - H2/test: `src/main/resources/db/schema/batch-metadata-h2.sql`
  - 기존 MySQL metadata schema용 archive/index 보강: `src/main/resources/db/schema/batch-metadata-retention-mysql.sql`
- `local`/`dev` 기본값은 다른 백엔드 서비스와 맞춰 원격 개발 MySQL `kimd0.iptime.org:23306`과 Redis `kimd0.iptime.org:26379`입니다.
- `local`/`dev` 접속값은 기존 백엔드 프로젝트처럼 `application-local.yml`, `application-dev.yml`에 직접 둡니다.
- `prod`는 DB와 Redis 값을 환경 변수로 명시 주입합니다.
- `prod`의 `STOCK_DB_URL`, `STOCK_BATCH_DB_URL`은 query string 없는 기본 JDBC URL로 넣습니다. 공통 JDBC 옵션은 설정 파일에서 `connectTimeout=5000`, `socketTimeout=30000`, `tcpKeepAlive=true`를 기본으로 붙입니다.
- batch는 business DB용 `spring.datasource`와 Spring Batch metadata용 `stock.batch.repository.datasource`를 분리합니다. business 원장은 `STOCK_SERVICE`, `JobRepository` metadata는 `STOCK_BATCH_METADATA`를 사용합니다.
- batch 운영 제어 상태와 수동 실행 요청은 물리적으로 분리된 서버 간에도 공유되도록 `STOCK_SERVICE`의 `stock_batch_job_control`, `stock_batch_job_lock`, `stock_batch_job_signal` 테이블을 기준으로 합니다.
- `stock_batch_job_control`은 스케줄러 자동 실행 runtime ON/OFF 상태를 job별로 저장합니다. `runtime_enabled`는 stock-back/어드민이 바꾸는 운영 중지 값이고, `scheduler_configured`는 stock-batch가 실제 서버 설정값을 동기화한 값입니다. `stock_batch_job_lock`은 같은 job의 중복 실행을 DB 락으로 막습니다. `stock_batch_job_signal`은 stock-back이 적재한 수동 실행/후처리 요청을 batch가 폴링해 처리하는 비동기 신호 큐입니다.
- 업무 배치는 실제 Spring Batch `Job`/`Step`으로 실행합니다. `businessDate`, `jobMode`, 업무 범위(`cycleId`, `scopeKey`, `phaseRevision`, `symbol`, `operation`, 정기 sweep의 `sweepAt`)를 stable identifying parameter로 사용하고 `signalId`, `triggeredAt`, `triggeredBy`, `requestId`, 실행 시각 성격의 `snapshotAt`은 non-identifying 추적·입력값으로 둡니다. 임의 `runId`/`runVersion`으로 매 실행을 새 `JobInstance`로 만들지 않습니다. 완료 결과를 다시 계산해야 하면 별도 correction workflow가 cycle의 `phaseRevision`을 명시적으로 올려야 합니다.
- Hikari 풀은 local/dev 기본 30개이며, prod는 `STOCK_DB_MAX_POOL_SIZE`, `STOCK_DB_CONNECTION_TIMEOUT`, `STOCK_DB_MAX_LIFETIME`, `STOCK_DB_KEEPALIVE_TIME`로 조정합니다.
- Batch metadata Hikari 풀은 local/dev 기본 12개이며 prod는 `STOCK_BATCH_DB_URL`, `STOCK_BATCH_DB_USERNAME`, `STOCK_BATCH_DB_PASSWORD`, `STOCK_BATCH_DB_MAX_POOL_SIZE` 계열 환경 변수로 조정합니다.
- 배치 업무 SQL은 `stock.batch.jdbc.query-timeout-seconds`로 statement query timeout을 적용합니다. 기본값은 30초이며 `STOCK_BATCH_JDBC_QUERY_TIMEOUT_SECONDS`로 조정합니다.
- DDL은 schema와 제약만 생성합니다. 기본 종목, 최초 가격, 자동 참여자는 seed하지 않으며 관리자 API 또는 smoke/test 데이터에서 명시적으로 등록합니다.
- EOD v1 정방향 11개 ALTER와 `stock_eod_application_rollback_alter.sql`은 stock-back canonical 사본과 byte 단위로 맞춥니다. 롤백 파일은 구버전 애플리케이션 호환용 비파괴 downgrade이며 신규 EOD 테이블·컬럼·인덱스를 지우지 않습니다. phase/eligible/lease 의미를 잃은 열린 신규 신호는 fail-closed 처리하고, 구버전 signal INSERT에 필요한 `next_attempt_at NULL` 계약만 복원합니다. 정방향 재배포 시 11개 ALTER를 다시 적용하면 기존 데이터 보존 상태에서 새 status 제약과 NOT NULL 계약이 복구됩니다.
- 운영 ALTER·호환 롤백은 stock-back과 stock-batch가 모두 종료되고 활성 업무 트랜잭션과 실행 중 Batch Job이 0인 유지보수 창에서만 실행합니다. exact schema downgrade가 필요하면 호환 롤백에 DROP을 추가하지 말고 적용 전 schema·영향 테이블 dump를 복원합니다.
- Redis key: `stock:price:{symbol}`. 값은 현재 단일 가격 문자열이며 `stock-back-service` 시장 가격 API가 우선 조회합니다. TTL 기본값은 60초이며 `STOCK_PRICE_CACHE_TTL_SECONDS`로 조정합니다.
- Redis channel: `stock.price.{symbol}`
- Redis에는 최신가 문자열과 pub/sub 메시지만 다루므로 `StringRedisTemplate` 기반 설정을 사용합니다. JSON Redis serializer는 현재 Spring Data Redis 4.x에서 removal deprecated 경고가 있어 사용하지 않습니다.
- 가격 수집 이력은 `stock_price_tick`에 append-only로 저장하며, `stock-back-service`의 `/api/stock/v1/markets/prices/{symbol}/ticks` API가 최근 이력을 조회합니다.
- 정산은 실행 시점의 계좌·보유·현재가를 다시 읽지 않습니다. `LEDGER_FROZEN`에서 `stock_holding_snapshot`에 고정한 수량·예약수량·평가가격과 `stock_close_account_snapshot`에 고정한 현금·예약금·보유 집계를 사용하며, 당시 `stock_price`가 없는 종목의 평가가격만 동결 시점 평단가로 fallback합니다. 이후 정산 Step은 계좌 snapshot 인덱스만 paging해 평가액, 총 보유량, 예약 매도수량, 양수 보유 포지션 수를 `portfolio_snapshot`에 기록하므로 실행 지연·재시작과 무관하게 같은 input hash를 냅니다.
- 자동장은 `stock_auto_participant`, `stock_auto_market_config`를 읽어 실제 `stock_order`에 자동 참여자 주문을 넣습니다.
- 자동장 주문 방향 강도는 참여자별 1~10 성향을 기본으로 하되, 주문장 종목의 최신 평가 보고서 점수가 있으면 함께 반영합니다. 최신 보고서 이벤트가 `DELETE`이거나 보고서가 없으면 보고서 신호 없이 참여자 성향만 사용합니다.
- 실제 주식시장 기능 확장 범위와 우선순위는 `../stock-back-service/STOCK_MARKET_FEATURE_ROADMAP.md`를 기준으로 봅니다.
- 기능별 현재 구현, 코드 위치, 다음 개발 순서는 `../stock-back-service/docs/market-simulation/00-overview.md`부터 확인합니다.
- batch 코드 파일별 책임은 `../stock-back-service/docs/market-simulation/13-code-ownership-map.md`, 기능별 변경 순서는 `../stock-back-service/docs/market-simulation/14-feature-change-playbooks.md`를 기준으로 봅니다.

## 외부 시세 Provider

기본값은 mock provider입니다. KIS OpenAPI로 전환하려면 아래 환경 변수를 설정합니다.

```bash
STOCK_BATCH_MARKET_DATA_PROVIDER=kis
KIS_BASE_URL=https://openapi.koreainvestment.com:9443
KIS_APP_KEY=...
KIS_APP_SECRET=...
KIS_MARKET_DIV_CODE=J
```

현재 설정 키는 다음과 같습니다.

- `stock.batch.market-data.provider`: `mock` 또는 `kis`
- `stock.batch.market-data.enabled`: 시세 갱신 job 활성화 여부
- `stock.batch.market-data.kis.base-url`
- `stock.batch.market-data.kis.app-key`
- `stock.batch.market-data.kis.app-secret`
- `stock.batch.market-data.kis.market-div-code`
- `stock.batch.order-book-execution.enabled`: 주문장 체결 job 활성화 여부
- `stock.batch.corporate-actions.enabled`: 기업 이벤트 반영 job 활성화 여부
- `stock.batch.auto-market.enabled`: 자동 참여자 주문 생성 job 활성화 여부
- `stock.batch.auto-market.fixed-rate-ms`: 자동장 주문 생성 dispatch 주기입니다. 기본값과 허용 최소값은 5000ms입니다. 전체 run dispatcher는 1 thread·queue 0으로 고정되어 이전 run이 실행 중이면 새 dispatch를 즉시 건너뛰며, 오래된 생성 작업을 적재해 주문·체결 DB 부하가 뒤늦게 폭발하지 않습니다.
- `stock.batch.auto-market.daily-regime.enabled`: 자동장 일일 방향/자산 선호 pre-create job 활성화 여부
- `stock.batch.auto-market.daily-regime.fixed-delay-ms`: 장 시작 전 일일 방향/자산 선호 pre-create 검사 주기
- `stock.batch.auto-market.daily-regime.pre-create-before-minutes`: 시뮬레이션 장 시작 몇 분 전부터 다음 거래일 방향/자산 선호를 미리 생성할지 결정합니다. 기본값은 30분입니다.
- `stock.batch.auto-market.generation-participant-chunk-size`: 한 트랜잭션에서 주문 생성까지 처리할 자동 참여자 수입니다. 기본값은 25, 허용 범위는 1~100입니다. 계좌·보유 상태 잠금과 매수 현금·매도수량 예약은 참여자별 단건 SQL이 아니라 청크별 set-based SQL로 처리하고, 주문도 Connector/J batch 재작성 설정에 의존하지 않는 명시적 multi-row INSERT 1회로 저장합니다. 한 INSERT는 최대 800행이며 범위를 벗어나면 실행 또는 서버 기동을 거부합니다.
- `stock.batch.auto-market.generation-profile-worker-count`: 한 auto-market run이 Redis ready profile queue에서 claim할 profile type 수입니다. 기본값은 9, 허용 최대값은 16입니다. 전체 실행 slot이 부족하면 남은 profile은 claim하지 않고 다음 run에서 처리합니다.
- 자동 주문의 기본 이론상 동시 계획 상한은 `min(profile-worker-count, thread-pool.max-size) × generation-participant-chunk-size × 참여자당 최대 주문 8개`, 즉 현재 기본값으로 1,800건입니다. 이 값은 평균이 아니라 capacity 상한으로 계산합니다. 청크를 25보다 키우거나 worker/slot을 늘릴 때는 두 값을 동시에 올리지 않고, 동일 MySQL 데이터·동시성에서 주문 API p95, 체결 p95/p99, commit, row-lock wait, deadlock을 비교해 주문·체결 TPS가 기준의 95% 미만이면 적용하지 않습니다. 상세 기준은 `docs/stock-eod-refactoring-plan-2026-07-15.md`의 거래량 비회귀 승인 게이트를 따릅니다.
- `stock.batch.auto-market.profile-queue.reconcile-fixed-delay-ms`: Redis ready profile queue reconcile 주기입니다. 기본값은 600000ms(10분), 허용 최소값은 60000ms입니다. 서버 시작 시에는 별도로 1회 reconcile을 수행합니다. 수동 복구는 `POST /internal/stock-batch/v1/jobs/auto-market-profile-queue/reconcile` endpoint를 사용합니다.
- `stock.batch.auto-market.generation-lease-seconds`: 주문 생성 대상으로 claim한 참여자-종목 스케줄의 lease 시간입니다. 주문 생성 실패 시 lease 만료 후 재시도할 수 있게 둡니다.
- `stock.batch.auto-market.generation-due-limit-per-symbol`: 한 회차에서 종목별로 조회할 주문 생성 대상 최대 수입니다. 기본값은 100, 허용 범위는 1~500이며 범위를 벗어나면 서버 기동을 거부합니다.
- `stock.batch.auto-market.generation-candidate-row-limit`: due 참여자와 활성 종목을 조합하는 중간 후보 행의 한 run 예산입니다. 기본값은 2,000, 허용 범위는 1~10,000입니다. profile claim 수는 `floor(row-budget / 활성 종목 수)`, profile별 참여자 수는 `floor(row-budget / (활성 종목 수 × 실제 claim profile 수))` 안으로 제한합니다. 기본 9 profile이 모두 due라면 3종목은 profile당 최대 74명·전체 약 1,998행, 100종목은 profile당 2명·전체 약 1,800행입니다. 활성 종목 수가 예산보다 많으면 조합 쿼리를 실행하지 않고 metric과 1회 WARN을 남깁니다. 종목 수 증가가 `참여자 × 종목² × 프로필` 중간 결과로 증폭되지 않습니다.
- due 스케줄 lease claim은 참여자별 UPDATE를 반복하지 않고 선택된 user key 전체를 한 번의 조건부 UPDATE로 선점합니다. 동시 claim 경합으로 일부 행만 갱신된 예외 상황에만 실제 소유 lease를 한 번 재조회합니다.
- 신규 참여자 스케줄 생성과 프로필 정책 변경도 참여자별 INSERT/UPDATE를 반복하지 않습니다. 기존 스케줄은 최대 500명씩 조회하고 누락 생성과 메타데이터 변경은 최대 100명씩 explicit multi-row INSERT/CASE UPDATE로 반영합니다. 501명 최초 생성은 6개 쓰기 문장, 100명 설정 변경은 1개 쓰기 문장으로 제한합니다.
- 주문 생성이 끝난 참여자 스케줄도 참여자별 UPDATE를 반복하지 않습니다. 실제 생성 청크에서는 최대 100명의 `profile_type`, `last_run_at`, 개별 `next_run_at`, interval, priority, lease 해제를 한 CASE UPDATE로 반영합니다. 기본 25명 청크의 스케줄 완료 원격 쓰기는 25회가 아니라 1회이며, 100명을 넘는 내부 호출도 100행 단위로 나뉩니다.
- 자동 주문 모멘텀 기준가격은 due 참여자가 존재할 때만 조회하고, 기존 최신가격 per-symbol `UNION ALL`을 최대 100종목씩 나눕니다. 종목 수가 늘어도 한 SQL 문자열·파라미터 수·파싱 비용이 무제한 커지지 않으며 윈도우 정렬 쿼리로 되돌리지 않습니다.
- 주 랜덤 regime와 30분 보조 modifier는 매 5초 실행마다 종목별 INSERT를 시도하거나 중복 키 예외를 정상 흐름으로 사용하지 않습니다. 현재 거래일·구간 값을 최대 500종목씩 일괄 조회하고 누락분만 최대 500행 multi-row로 저장합니다. 같은 구간의 정상 재실행은 두 테이블 모두 DB 쓰기 0회이며, 501종목 경계 테스트는 최초 일별/보조 저장이 각각 2문장, 같은 구간 재실행이 0문장임을 검증합니다.
- `stock.batch.auto-market.max-open-order-quantity-multiplier`: 자동 참여자 계좌·종목·방향별 미체결 누적 수량 상한을 종목 `max_order_quantity`의 몇 배로 둘지 정합니다. 기본값은 10, 허용 범위는 1~100이며, 극단적으로 한쪽 잔량이 몰리면 같은 방향 신규 주문 수량도 축소합니다.
- `stock.batch.auto-market.deadlock-retry-max-attempts` / `deadlock-retry-backoff-ms`: 자동장 주문 생성 중 계좌/보유 예약 update에서 deadlock이 발생했을 때 같은 chunk 트랜잭션을 짧게 재시도하는 횟수와 backoff입니다.
- `stock.batch.auto-market.thread-pool.core-size` / `max-size` / `queue-capacity`: 자동장 주문 생성 profile shard를 처리하는 execution thread pool입니다. 기본값은 12/12/0입니다. core/max는 1~16, queue는 정확히 0만 허용합니다. run thread는 executor 포화 시 profile 작업을 직접 실행하지 않고, 실행 slot을 확보한 뒤에만 Redis profile을 claim합니다.
- `stock.batch.auto-market.run-dispatcher.thread-pool.core-size` / `max-size` / `queue-capacity`: auto-market 전체 run을 제출하는 dispatcher pool입니다. 1/1/0만 허용해 전체 run 중첩과 backlog를 막고, 한 run 내부의 bounded profile worker 병렬성만 사용합니다.
- `stock.batch.auto-market-order-expiry.enabled`: 자동장이 낸 미체결 주문 만료 job 활성화 여부
- `stock.batch.auto-market-order-expiry.fixed-delay-ms`: 자동장 미체결 주문 만료 검사 주기. 기본값은 10000ms, 허용 최소값은 5000ms입니다.
- `stock.batch.auto-market-order-expiry.expiry-chunk-limit`: 한 회차에서 취소할 자동장 만료 주문 후보 최대 수
- `stock.batch.auto-market-order-expiry.symbol-limit-per-run`: 한 만료 run에서 처리할 종목 상한입니다. 기본값은 100, 허용 범위는 1~500입니다. 100종목을 넘으면 현실 10초 버킷 기준의 결정적 slice를 순환해 여러 서버에서도 같은 구간을 선택하고 한 run이 auto-market scheduler를 장시간 독점하지 않습니다.
- `stock.batch.listing-auto-market.enabled`: 상장주관사 자동계정 주문 공급 job 활성화 여부
- `stock.batch.listing-auto-market.fixed-delay-ms`: 상장주관사 자동계정 주문 공급 주기. 기본값은 10000ms, 허용 최소값은 5000ms입니다.
- `stock.batch.listing-auto-market.symbol-limit-per-run`: 한 공급 run에서 처리할 종목 상한입니다. 기본값은 100, 허용 범위는 1~500이며 만료 작업과 같은 결정적 10초 slice를 사용합니다. 현재 3종목에서는 기존처럼 모두 처리합니다.
- 만료·상장주관사 공급의 symbol-lock skip metric은 종목 코드를 tag로 사용하지 않는 전역 counter입니다. 종목 식별은 debug 로그에만 남겨 종목 수 증가가 Micrometer 시계열 cardinality와 heap 사용량으로 이어지지 않습니다.
- 만료·상장주관사 공급의 활성 종목 조회는 자동 주문 생성용 최신 보고서 점수·분포 편향 상관 서브쿼리를 사용하지 않습니다. 두 작업에 필요한 TTL·가격·수량·틱 정보만 읽는 경량 설정 쿼리로 분리해 10초 주기 보고서 원장 접근을 제거합니다.

상장주관사 자동계정은 기관형 유동성 공급 정책으로 운용합니다. `SELL_ONLY`, `BUY_ONLY`, `TWO_SIDED` 중 활성 방향을 선택하고, 매수·매도별 목표 미체결 잔량을 유지합니다. 실제 신규 주문은 `유효 목표 잔량 - 현재 미체결 잔량`만큼만 생성하며 한 주문의 크기는 `max_order_quantity`를 넘지 않습니다. `TWO_SIDED`는 `target_holding_quantity ± inventory_band_quantity`를 재고 허용 구간으로 사용합니다. 매수 유효 목표는 `min(target_buy_quantity, 재고 상한 - 현재 보유량)`, 매도 유효 목표는 `min(target_sell_quantity, 현재 보유량 - 재고 하한)`이므로 밴드 안에서는 양쪽 호가가 함께 유지되고, 밴드 밖에서는 목표 쪽으로 재고를 줄이는 방향만 남습니다. 각 방향의 미체결 전량이 단독 체결돼도 재고 구간을 넘지 않도록 독립적으로 제한합니다. `BUY_ONLY`와 `SELL_ONLY`는 기존처럼 목표 보유 수량을 단방향 도달 한도로 사용합니다. 목표·밴드·방향이 바뀌면 초과 호가를 취소한 뒤 필요한 수량만 다시 채웁니다. 매수·매도별 가격 분산 방향은 `UP`, `DOWN`, `RANDOM`으로 설정하며, 같은 주관사 계정의 반대 호가와 자기교차하지 않도록 최종 가격을 한 틱 밖으로 보정합니다.
- `stock.batch.auto-participant-cash-flow.enabled`: 자동 참여자 주기 입금 job 활성화 여부
- `stock.batch.auto-participant-cash-flow.fixed-delay-ms`: 자동 참여자 주기 입금 검사 주기. 기본값은 300000ms(5분)입니다. 신규·수정 운영 설정은 `DAY`·`MONTH`·`YEAR`만 허용합니다. 기본 coordinator가 활성화되면 독립 scheduler는 실행하지 않고 거래일별 00시 이후 phase에서 due-sweep를 한 번만 실행하며, 한 거래일에 최대 한 번만 지급하고 지나간 회차는 소급 지급하지 않습니다. 과거 `SECOND`·`MINUTE`·`HOUR` row는 판독 호환을 위해 enum에 남기지만 신규 설정으로 만들지 않습니다. coordinator를 명시적으로 끈 호환 모드만 `PRE_OPEN(00:00~06:00)`에서 5분 주기로 검사합니다. 두 모드 모두 정규장에는 runtime control, JobRepository, 계좌·현금흐름을 조회하지 않아 주문·체결과 계좌 잠금을 경쟁하지 않습니다. 지급 여부는 시뮬레이션 시간 기준이고 polling 간격은 실제 서버 시간 기준입니다.
- `stock.batch.auto-participant-cash-flow.account-chunk-size`: 야간 자동 월급·정기 입금의 계좌 청크 크기입니다. 기본값은 200, 허용 범위는 1~1000입니다. 최근 지급 조회의 `IN` 크기와 한 업무 트랜잭션이 갱신하는 계좌 수를 함께 제한하며, 계좌·사유별 최신 지급 시각만 DB에서 집계해 과거 원장을 JVM에 전부 적재하지 않습니다. 같은 `requestId` 재시작은 `stock_auto_participant_cash_flow_run`의 마지막 완료 계좌 PK부터 이어가며, 현금·현금흐름·cursor를 같은 청크 트랜잭션으로 커밋해 후반 청크 실패가 앞 계좌의 중복 지급으로 이어지지 않습니다. 이 제어 테이블은 실행당 1행이고 정규장에는 coordinator가 이 Job을 실행하지 않습니다.
- 기업행사 현금/PRE_OPEN 변환과 자동 월급·정기 입금 서비스는 coordinator 우회·Job 재시작에도 열린 시장에서 실행되지 않도록 실제 대상 원장을 읽기 전에 작은 시장 설정/fence 테이블을 재검증합니다. 이 방어 조회는 `stock_order`·`stock_execution`을 읽지 않으며, 열린 장에서는 계좌·권리·현금흐름 조회 전에 즉시 실패합니다.
- `stock.batch.corporate-action.account-chunk-size`: 배당 지급, 자동 유상증자 청약, 무상증자·주식배당·유상증자 신주 반영에서 한 업무 트랜잭션이 처리할 계좌 상한입니다. 기본값은 200, 허용 범위는 1~1000입니다. 각 청크는 `REQUIRES_NEW`로 커밋하고 계좌별 processing ledger를 함께 기록하므로 대상 계좌 수가 늘어도 하나의 연결·행 잠금을 전체 대상 처리 시간 동안 보유하지 않습니다.
- `stock.batch.corporate-action.action-batch-limit`: 한 기업행사 Step이 선택하는 due event 상한입니다. 기본값은 25, 허용 범위는 1~200입니다. 계좌 1만 개·기본 200계좌 청크에서는 이벤트 하나가 최대 약 50개의 짧은 업무 트랜잭션을 만들 수 있으므로, due 이벤트 200개를 기본값으로 한꺼번에 소진하지 않습니다. 지급일·권리락일·상장일 순서의 앞선 event만 읽고 남은 event는 cycle 재시도로 넘깁니다. 자동청약이 완료된 event는 processing ledger로 후보에서 제외하므로 상태가 그대로인 완료 event가 다음 batch를 가로막지 않습니다. Spring Batch의 마지막 검증에서 남은 작업 때문에 **기업행사 CASH/PREOPEN phase**가 실패하고 현재 시도의 완료 Step write count가 양수이면 `BOUNDED_PROGRESS`로 기록해 기본 30초 뒤 같은 JobInstance를 재개합니다. 정상적으로 청크를 소진하는 backlog를 장애 지수 백오프로 오인하지 않으며, 처리 진전이 0인 기업행사 실패와 보고서·정산·시세 등 다른 Job의 부분 쓰기 실패는 30~900초 지수 백오프에 포함합니다. 다른 Job의 양수 write count를 빠른 재개의 근거로 쓰면 같은 대형 원장 범위를 30초마다 반복할 수 있으므로 금지합니다.
- 기업행사 phase 완료 판정과 개장 readiness는 기업행사·처리 원장의 due 상태만 집계합니다. 완료 여부를 확인하려고 `stock_order`·`stock_execution`·전체 계좌를 다시 읽지 않으며, 스냅샷 누락이나 미처리 action이 있으면 phase를 성공 처리하지 않고 재시도 상태로 남깁니다.
- 체결 계좌 실시간 일일요약 flush는 pending account-day 누적값이 있고 시장이 열린 동안에만 실행합니다. 18시 fence가 닫힌 뒤에는 파생 요약 UPSERT를 중단해 원장 동결과 DB commit을 경쟁시키지 않고, 남은 누적값은 완료 거래일 검증으로 폐기하거나 00시 이후 해당 거래일의 원본 체결 인덱스 범위에서 재구축합니다. 포트폴리오 정산은 이 인메모리 요약을 권위 입력으로 사용하지 않습니다.
- `stock.batch.market-close.enabled`: 장 마감 기준가 롤오버 job 활성화 여부
- `stock.batch.market-close.poll-fixed-delay-ms`: 시뮬레이션 세션과 장마감 단계 감지 주기. 기본값은 10000ms이며, 실제 거래 차단은 최종 주문·체결 트랜잭션의 DB 세션 fence가 담당합니다.
- `stock.batch.market-close.order-capture-chunk-size`: 전체 장마감에서 미체결 주문 cohort를 PK keyset으로 동결하는 한 커밋의 상한입니다. 기본값은 1000입니다. `max(order_id)`가 영속 재시작 checkpoint이므로 실패 후 이미 캡처한 주문을 다시 스캔하지 않습니다.
- `stock.batch.market-close.order-cancel-chunk-size`: 전체 장마감과 수동 종목 취소·상장폐지 정리에서 한 트랜잭션이 잠그고 반환할 미체결 주문 상한입니다. 기본값은 500입니다. 종목 lock은 전체 취소 동안 유지하되 DB 트랜잭션과 연결은 청크마다 반환하여 대량 주문 취소가 계좌·보유 테이블을 장시간 잠그지 않게 합니다.
- `stock.batch.market-close.holding-snapshot-account-chunk-size`: 장마감 전 보유·예약수량을 한 번에 동결할 계좌 상한입니다. 기본값은 500이며 snapshot의 `max(account_id)`로 재개합니다.
- `stock.batch.market-close.account-snapshot-chunk-size`: 현금·예약금·외부 순입금과 정산 대상 계좌를 한 번에 동결할 계좌 상한입니다. 기본값은 500입니다. 첫 chunk의 cash-flow watermark를 이후 chunk와 재시작에서도 고정하여 계좌별 기준 시각이 달라지지 않게 합니다.
- `stock.batch.market-close.reconciliation-account-chunk-size`: 예약금 반환 후 현금 대사를 한 번에 갱신할 계좌 상한입니다. 기본값은 500입니다.
- 주문 캡처 청크는 1~10000, 주문 취소·반환 청크는 1~5000, 보유·계좌·대사 청크는 1~2000만 허용합니다. 범위를 벗어난 설정은 장기 잠금·undo/redo·DB 연결 독점을 막기 위해 서버 기동 시 거부합니다. 허용 범위 안의 증설도 실데이터 MySQL에서 주문·체결 p95/p99와 lock wait 회귀를 검증한 뒤 적용합니다.
- 이미 `stock_eod_immutable_snapshot_alter.sql`을 적용한 DB에는 먼저 `stock_eod_report_participant_snapshot_alter.sql`, 이어서 `stock_eod_volume_indexes_alter.sql`을 적용합니다. 전자는 야간 계좌 수급 보고서의 참가자 유형을 저빈도 `stock_close_account_snapshot`에 동결해 보고서 재실행이 변경 가능한 계좌·프로필 설정을 다시 조인하지 않게 합니다. 후자는 immutable snapshot 테이블에 계좌 정산 cursor, 주문 반환 cursor, 주문 캡처 재시작 stream 인덱스를 조건부 생성하고 기존 주문 스냅샷에 캡처 당시 상태 cursor를 보정합니다. 두 ALTER 모두 `stock_order`·`stock_execution`에 열이나 인덱스를 추가하지 않으며, 참가자 유형 보정도 두 hot ledger를 읽지 않습니다. 보정 UPDATE와 ALTER는 서비스가 실행 중일 때 수행하지 않고, 백엔드·배치 서버를 사용자가 모두 종료했다고 확인한 뒤에만 실행합니다.
- `stock.batch.market-close.settlement-delay-simulation-minutes`: 장마감 기준시각 이후 포트폴리오 정산을 지연할 시뮬레이션 분입니다. 기본값은 10분이며 실제 실행 가능 시각은 `max(원장 동결 완료 시각, 장마감 기준시각 + 설정값)`입니다. 주문량 증가로 원장 동결이 오래 걸려도 정산이 동결 완료보다 먼저 실행되지 않으며, 대기 때문에 장마감 Job이나 DB 트랜잭션을 열어두지 않습니다.
- 전체 장마감은 fence drain 뒤 열린 주문 symbol을 기존 `idx_stock_order_market_status_symbol`에서만 찾고, `(market_type, status, symbol)` 고정 prefix 뒤 InnoDB가 확장한 clustered PK `id`를 이용해 상태·종목별 frozen cohort를 1,000건씩 keyset 캡처한 뒤 500건씩 반환합니다. 실제 MySQL 격리 테스트는 이 쿼리가 filesort로 회귀하지 않는지도 검사합니다. 매일 `order_id=0`부터 과거 종료 주문 전체를 다시 훑지 않으며 이 최적화를 위해 `stock_order` 쓰기 인덱스를 새로 추가하지 않습니다. 각 반환 청크에서는 계좌 ID 오름차순, frozen 매도 주문에 실제 존재하는 복합키 `(account_id, symbol)`만 오름차순, 정확한 주문 PK 순으로 잠그고 취소·현금/예약수량 반환·`released_at` checkpoint를 같은 트랜잭션에 커밋합니다. 계좌 집합과 종목 집합의 교차조합을 잠그지 않으므로 종목 수가 늘어도 무관한 보유 row 잠금이 `계좌 수 × 종목 수`로 증폭되지 않습니다. 따라서 거래량이 커져도 하나의 전역 잠금 트랜잭션이 모든 계좌·주문을 보유하지 않고, 실패 후 완료 청크의 반환을 중복 수행하지 않습니다.
- 취소 완료 검증도 캡처된 주문마다 `stock_order`를 다시 PK 조인하지 않습니다. 반환 청크가 `주문 취소 + 예약 반환 + released_at`을 원자적으로 커밋하므로, 마지막 검증은 `stock_close_open_order_snapshot(close_cycle_id, released_at, order_id)`에서 미반환 건수만 확인합니다. 거래량이 늘어도 장마감 완료 판정을 위해 hot order 원장을 다시 읽지 않습니다.
- 계좌 스냅샷이 여러 청크로 나뉘는 동안의 기준시각은 백엔드 `MarketLedgerFreezeGuard`가 보호합니다. 관리자 입출금, 계좌 생성·탈퇴/복구, 자동 참여자 계좌·초기자금, 유상증자 수동청약, 신규 주문장 종목 상장 같은 저빈도 원장 변경만 거래일 제어행 공유 잠금을 사용합니다. 먼저 시작한 변경은 close의 배타 전환이 기다려 동결 입력에 포함하고, close cycle이 `CLOSE_REQUESTED`이면 새 변경은 계좌 잠금 전에 거부합니다. `LEDGER_FROZEN` 이후 변경은 다음 거래일 원장으로 허용하며 주문·체결 경로에는 이 전역 잠금을 추가하지 않습니다.
- 배치 자동 월급·정기 입금도 200계좌 청크마다 같은 거래일 제어행을 공유 잠금하고 freeze phase를 재검증합니다. 수동 API나 오래된 Job이 장마감과 겹쳐도 먼저 시작한 청크까지만 동결 입력에 포함되고 이후 청크는 계좌 UPDATE 전에 실패합니다. 완료 cursor는 계좌 청크의 현금 반영과 같은 업무 트랜잭션에서만 전진하므로 프로세스 종료 뒤 같은 JobInstance를 재시작해도 이미 커밋한 계좌를 건너뜁니다. 이 검증과 cursor SQL은 야간 현금 청크에만 있으며 주문·체결 worker에는 실행되지 않습니다.
- 기업행사의 각 `REQUIRES_NEW` 청크도 계좌·보유·권리·종목을 잠그기 전에 같은 거래일 제어행, enabled 시장/fence, full-market freeze phase를 재검증합니다. stage 진입 뒤 장마감이 시작되거나 비정상 개장이 경쟁해도 뒤 청크는 원장 변경 전에 중단되며, 청크당 소형 제어 조회 2회만 실행하고 `stock_order`·`stock_execution`은 읽지 않습니다. 따라서 장마감 정합성을 얻기 위해 정규장 주문·체결 핫패스에 전역 원장 잠금을 추가하지 않습니다.
- 자동 월급·기업행사·PREOPEN 시세 stage는 대상 cohort를 읽기 전 같은 소형 허가를 preflight로 한 번 더 수행합니다. 이미 freeze/open 경계가 시작됐으면 프로필·계좌·권리·현금흐름·provider 후보 조회 자체를 생략하고, preflight 뒤 경계 경쟁은 각 쓰기 청크의 재검증이 막습니다.
- PREOPEN strict 시장 데이터는 대상/provider 조회 전에 OPEN을 거부하고, 외부 provider 호출을 DB 트랜잭션 밖에서 마친 뒤 종목별 가격 쓰기 직전에만 같은 저빈도 원장 허가를 얻습니다. 빈 보유 정리도 DELETE 전에 허가를 얻습니다. coordinator를 끈 호환 시장 데이터의 실제 가격 쓰기는 작은 business-state 행의 공유 close-boundary permit만 잡습니다. MySQL 주문·체결은 종목 fence만 잠그므로 이 permit과 직렬화되지 않으며, close의 배타 business-state 잠금과만 경합해 동결 중 가격 변경을 막습니다.
- 전체 장마감의 Redis symbol-lock 대상도 `stock_market_session_fence`·시장 설정·상장 종목 제어 테이블에서만 구성합니다. 등록 종목 수와 무관한 대형 `stock_order`·`stock_holding` UNION 스캔으로 장마감 진입이 지연되지 않으며, 미등록 과거 주문은 frozen cohort 취소에는 계속 포함됩니다.
- 사용자 취소·정정의 세션 검증은 주문 소유권·종목·시장 유형·방향과 종목 fence를 하나의 PK/유일키 기반 SQL로 조회합니다. 계좌와 주문 descriptor를 별도로 사전 조회하지 않으며, MySQL에서는 fence 행만 공유 잠금합니다. 실제 쓰기는 계좌→매도 보유→정확한 주문 PK 순서로 잠그고 이미 잠긴 계좌·보유 객체로 예약을 반환하므로, 체결 경로와 잠금 순서가 같고 중복 계좌·보유 조회도 없습니다.
- 계좌 탈퇴·자동 참여자 철회 시 미체결 정리는 호출자가 잠근 계좌를 기준으로 후보를 읽은 뒤, 매도 보유를 종목 오름차순 단일 조회로 잠그고 정확한 주문 PK 잠금·취소 UPDATE를 500건씩 수행합니다. 주문당 보유 조회·주문당 UPDATE·무제한 `IN` 절을 피하며, 이 계좌 전용 경로를 전체시장 장마감에는 사용하지 않습니다.
- 체결 worker의 사전 gate는 `stock.batch.order-book-execution.worker.gate-refresh-ms`(기본 1000ms) 동안 공유됩니다. worker 수와 무관하게 작은 시장 설정/fence/business-state 조회를 기본 초당 1회 이하로 제한하고, 실제 정확성은 각 체결 트랜잭션의 fence PK 공유 잠금이 담당합니다. 원시 세션이 REGULAR가 아니면 DB gate도 조회하지 않습니다.
- 주문 API fence는 `stock.trading.session.fence.duration`의 고정 operation 태그, 배치 자동주문·체결 fence는 `stock.orderbook.session.fence.duration`으로 시간을 기록합니다. 종목 코드를 태그로 사용하지 않으므로 거래 종목 수가 늘어도 metric cardinality가 증가하지 않으며, 추가 SQL 없이 shared-lock 대기 p95/p99를 cut-over 기준과 비교할 수 있습니다.
- 자동 주문·자동 주문 만료·상장주관사 공급·fallback 체결 scheduler도 DB 시장 gate가 닫혀 있으면 주문 후보·계좌·보유 테이블을 읽기 전에 반환합니다. 이 admission gate는 `stock_order`와 `stock_execution`을 조회하지 않습니다.
- 10초 시장 세션 동기화는 시뮬레이션 clock을 한 번만 읽고 이미 정합한 상태를 config/fence 소형 조회로 확인한 뒤 즉시 반환합니다. 정상 정규장에는 외곽 쓰기 트랜잭션, 전 종목 fence `FOR UPDATE`, 상태 UPDATE가 없으므로 주문·체결 공유 fence와 polling성 잠금·commit 경합을 만들지 않습니다. prefix scheduler는 가장 오래된 미정산 cycle과 활성 거래일 제어행만 추가로 조회합니다. PRE_OPEN을 놓쳐 원시 시각이 REGULAR가 됐어도 실제 시장이 모두 `CLOSED`이면 오래된 freeze·정산을 계속 복구하고, 시장이 하나라도 `OPEN`이면 launcher와 JobRepository 전에 반환합니다. 이 복구 판정은 `stock_order`·`stock_execution`을 읽지 않습니다.
- `stock.batch.post-close.lease-seconds`: 거래일별 후처리 phase를 한 배치 인스턴스가 선점하는 lease 시간입니다. 기본값은 180초입니다. cycle/attempt 조회는 coordinator에서만 수행하며 주문·체결 hot path에는 넣지 않습니다.
- coordinator가 실행하는 native Job은 실제 Spring Batch `JobExecution` ID를 현재 phase attempt에 연결합니다. `cycleId`가 없는 정기 Job은 이 연결을 위한 업무 DB 조회를 실행하지 않고, close·settlement는 기존 첫 Step claim에서 연결하여 장마감 hot path에 중복 조회를 추가하지 않습니다.
- `stock.batch.post-close.retry-base-seconds`, `retry-max-seconds`, `deferred-retry-seconds`: 처리 진전이 없는 실패 phase는 **해당 phase의 실패 이력** 기준으로 30초부터 최대 900초까지 지수 백오프하고, 정책·runtime control 때문에 연기된 phase는 기본 60초 뒤 다시 판정합니다. bounded 기업행사 CASH/PREOPEN Step이 양수 write count를 커밋한 뒤 잔여 작업 검증에서 실패한 경우만 `BOUNDED_PROGRESS`로 구분해 30초 기본 간격으로 이어서 처리합니다. 보고서·정산·시세 등 다른 Job은 양수 write count가 있어도 실제 장애로 보고 지수 백오프합니다. cycle 전체 `attempt_count`나 정상 기업행사 backlog continuation을 지수에 포함하지 않으므로 앞 단계가 많거나 due action이 200건을 넘어도 불필요하게 15분까지 지연되지 않습니다. 다음 실행 가능 시각은 `stock_post_close_cycle.next_retry_at`에 영속화되므로 서버 재시작·다중 인스턴스에서도 10초 poll마다 같은 Spring Batch Job과 `BATCH_*` 커밋을 반복하지 않습니다. 이 대기는 coordinator 제어행과 phase attempt 소량 이력에만 적용되며 주문·체결 hot path에는 조회나 잠금을 추가하지 않습니다.
- coordinator의 정규장 10초 poll은 기존 cycle 인덱스에서 가장 오래된 미완료 cycle 본문을 한 SQL로 읽습니다. ID 조회 후 재조회나 명시적 read transaction/commit을 만들지 않으며 `stock_order`·`stock_execution`에는 접근하지 않습니다.
- `stock.batch.post-close.report-aggregation.enabled`: 종료 종목·계좌 일별 보고서 집계 Job을 독립적으로 중지하는 설정입니다. 이 Job은 18시 원장 동결 트랜잭션에서 제거되었고, `LEDGER_FROZEN`·포트폴리오 정산 이후 야간 단계에서만 실행하여 주문·체결 테이블의 장중 CPU·I/O 경합을 막습니다.
- 보고서 집계 서비스 자체도 full-market cycle의 phase가 정확히 `CORPORATE_CASH_APPLIED`일 때만 실행합니다. coordinator 외의 수동·오래된 호출이 freeze/정산과 겹쳐 `stock_execution` 범위 집계를 시작하거나, 이미 완료된 보고서를 다시 삭제·작성하는 경로를 fail-closed로 막습니다.
- coordinator가 호출하는 각 launcher도 이미 읽은 full-market cycle 행에서 정확한 선행 phase를 다시 확인합니다. 자동 현금은 `PORTFOLIO_SETTLED`, 기업행사 현금은 `OVERNIGHT_CASH_APPLIED`, 보고서는 `CORPORATE_CASH_APPLIED`, 보유 정리·PRE_OPEN 변환은 `REPORTS_AGGREGATED`, 시세 준비는 `PREOPEN_SECURITY_TRANSFORMS_APPLIED`, regime·프로필 큐는 `MARKET_DATA_PREPARED`, readiness는 `AUTO_MARKET_PREPARED`에서만 시작합니다. 이 검증은 제어행 1회 조회뿐이며 `stock_order`·`stock_execution`을 읽기 전에 실패하므로 직접 호출이나 오래된 신호도 무거운 야간 작업을 정규장/잘못된 단계에 겹치게 하지 못합니다.
- 보고서·차트·관리자 일별 수급 조회는 close run 완료만 보지 않고 연결된 cycle이 `REPORTS_AGGREGATED` 이상인지 확인합니다. 종목별 야간 집계 도중 장애가 나도 부분 보고서를 노출하지 않으며, 추가 조인은 `close_run_id` 인덱스를 쓰는 소형 제어 테이블 조회입니다.
- 계좌 포트폴리오 이력·수익률 순위·관리자 총자산 이력은 정산 chunk 일부가 먼저 커밋되어도 full-market cycle이 `PORTFOLIO_SETTLED`에 도달하기 전에는 노출하지 않습니다. 기존 cycle 없는 snapshot만 legacy 확정 데이터로 허용하고, 조회는 portfolio의 계좌/날짜 인덱스와 cycle PK만 사용하므로 주문·체결 원장을 건드리지 않습니다.
- `stock.batch.post-close.coordinator.enabled=true`이면 현금흐름·기업행사·시세·빈 보유·일일 regime의 기존 독립 scheduler와 PRE_OPEN 프로필 큐 준비는 runtime-control이나 업무 테이블을 읽기 전에 반환합니다. 단, 정규장 프로필 ready-queue 유실 복구는 기본 10분 주기로 작은 config·schedule 제어 테이블만 읽고 Spring Batch metadata와 주문·체결 원장은 사용하지 않습니다. 같은 야간 작업은 coordinator 전용 단일 스레드에서만 실행되어 무거운 Job이 겹치지 않습니다.
- PRE_OPEN 시장 데이터 갱신은 외부 시세 provider 호출을 DB 트랜잭션 밖에서 수행하고, 성공한 종목의 가격·tick 쓰기만 종목별 짧은 `REQUIRES_NEW`로 커밋합니다. 대상 주문 조회도 `market_type='VIRTUAL_PRICE'`로 제한하므로 주문장 체결 원장과 장시간 연결을 공유하지 않습니다. 미체결 주문과 보유는 각 소스에서 symbol별로 먼저 집계한 뒤 합치므로 주문·계좌가 늘어도 UNION 중간 결과가 원시 행 수만큼 커지거나 같은 종목을 provider에 반복 요청하지 않습니다.
- `stock.batch.post-close.coordinator.enabled`: 거래일별 phase coordinator를 활성화합니다. 기본값은 `true`이며, 활성화 시 기업행사·자동 입금·시세 갱신·PRE_OPEN 프로필 큐 준비·빈 보유 정리의 독립 주기 scheduler는 실행하지 않고 coordinator만 순서대로 실행합니다. 정규장에는 주문 생성용 ready-queue의 bounded 복구만 별도로 허용합니다.
- `stock.batch.post-close.readiness.enabled`: 다음 장 개장 전 스냅샷·일별 보고서·일일 regime·시장 상태 누락을 검사하는 Spring Batch Job을 제어합니다. 실패하면 `READY_TO_OPEN`으로 이동하지 않아 개장이 차단됩니다.
- 전용 scheduler는 종료 신호 뒤 아직 시작하지 않은 delayed/periodic polling trigger를 취소하고, 이미 실행 중인 업무만 `stock.batch.scheduler-pools.shutdown-await-seconds` 범위에서 기다립니다. 따라서 서버 종료 중 새 EOD·자동시장 조회가 시작되거나 미래 trigger 때문에 종료가 지연되지 않습니다.
- 장마감·정산 처리량은 `stock_post_close_cycle_metric`의 cycle별 한 행에 기록합니다. 관리자 EOD 화면과 `GET /api/stock/v1/markets/batch-jobs/eod/overview`는 이 요약과 제어 테이블만 조회하며 `stock_order`, `stock_execution`, 현금흐름 원장을 폴링 때마다 다시 집계하지 않습니다.
- 05:30 개장 readiness는 포트폴리오·거래일·시장·종목 fence·가격·기업행사·regime·프로필 큐·runtime identity의 고정 10개 결과를 `stock_post_close_readiness_check`에 cycle당 최대 10행으로 저장합니다. 실패 Step도 진단행은 별도 트랜잭션으로 보존하며 관리자 화면은 이 PK 범위만 읽어, 15초 polling이 주문·체결·기업행사 원장을 다시 집계하지 않습니다.
- 외부 순입금 스냅샷은 직전 거래일 full-market cycle의 전역 cash-flow PK watermark 이후 범위만 집계합니다. 복구로 cycle ID와 거래일 생성 순서가 달라져도 `business_date` 기준 직전 cycle을 선택하며, 이 선행 조회는 EOD 제어 인덱스만 사용하고 주문·체결 원장을 읽지 않습니다.
- 자동 월급이 꺼져 있으면 야간 현금 phase는 지급 0건으로 정상 완료되어 다음 장 준비를 막지 않습니다. 수동 월급 signal은 해당 거래일 cycle이 `PORTFOLIO_SETTLED`에 도달하기 전에는 claim·attempt·JobRepository 이력을 만들지 않으며, 이 대기 조건은 소형 cycle 유일키만 조회합니다.
- 관리자 EOD 조회는 `/admin/system/eod` 화면이 열려 있을 때만 기본 15초 주기로 실행하고 백그라운드 폴링을 하지 않습니다. 10초 미만 주기로 조정하려면 실제 주문·체결 동시 부하에서 DB CPU, lock wait, 주문 API p95 회귀를 먼저 검증해야 합니다.
- 장 상태는 장마감 즉시 `CLOSED`로 내려 주문/체결을 막습니다. 수동 시간 제어는 Job을 직접 실행하지 않고 `오늘 18:00`, `다음 일자 00:00`, `다음 장 06:00` 경계로만 이동합니다. `다음 일자 00:00`은 원장 동결과 포트폴리오 정산 완료를, `다음 장 06:00`은 야간 현금·기업행사·보고서·가격·자동시장·readiness까지 완료된 `READY_TO_OPEN`을 요구합니다. 판정 거래일은 원시 clock의 날짜 계산이 아니라 `stock_market_business_state.active_business_date`를 사용하며 PRE_OPEN에는 `preparing_business_date=active_business_date+1일` 정합성도 확인합니다. 완료 판정은 현재 활성 계좌나 snapshot을 전수 COUNT하지 않고 cycle 유일키와 `stock_post_close_cycle_metric` 한 행만 읽습니다.
- 포트폴리오 Writer는 현재 시뮬레이션 시각을 자체 조회하지 않고 Job이 고정한 거래일·스냅샷 시각만 사용합니다. 따라서 18:10 이후 지연 실행이나 장애 재시작에서도 정산 기준 시각이 바뀌지 않습니다. MySQL 저장은 최대 500계좌의 명시적 multi-row UPSERT로 처리하여 JDBC batch 재작성 설정과 무관하게 기본 200계좌 정산 chunk를 한 원격 쓰기로 저장합니다.
- `stock.batch.settlement.enabled`: 포트폴리오 정산 job 활성화 여부
- 포트폴리오 정산 reader는 `(close_cycle_id, settlement_target, account_id)` 전용 인덱스로 `stock_close_account_snapshot`만 paging합니다. 보유 평가액·수량·예약 매도·포지션 수는 18시 동결 때 계좌당 한 번 저장하고 정산 시작 전 cycle 전체를 한 번 대사하므로, 페이지마다 `stock_holding_snapshot`을 다시 `GROUP BY`하지 않습니다.
- 정산 완료 검증은 같은 frozen cohort와 `portfolio_snapshot`을 한 번 조인한 결과에서 입력 hash·산식 버전뿐 아니라 현금·평가액·총자산·수익률·보유수량·예약매도·포지션 수도 다시 계산해 전부 대조합니다. 기존 완료 검증 쿼리에 포함되므로 추가 원장 스캔이나 정규장 이중 쓰기는 없으며, 값이 하나라도 다르면 `PORTFOLIO_SETTLED`로 전진하지 않습니다.
- `stock.batch.post-close.report-aggregation.symbol-chunk-size`: 야간 종목·계좌 보고서가 한 번의 Spring Batch 체크포인트 사이에서 처리하는 종목 cohort 상한입니다. 기본값은 25, 허용 범위는 1~200입니다. `(close_cycle_id, symbol)` 키셋으로만 다음 cohort를 읽고 실제 체결 집계는 종목별 짧은 `REQUIRES_NEW`에서 실행합니다. 장애 시 다시 읽는 원본 체결 범위도 최대 한 cohort로 제한하면서 종목마다 Batch metadata COMMIT을 만드는 쓰기 증폭은 피합니다. 현재 3종목은 한 번에 처리되고, 100종목으로 늘어나도 기본 설정에서는 Step당 네 번의 체크포인트만 생성됩니다.
- `stock.batch.metadata-retention.enabled`: 완료 Spring Batch metadata의 compact archive 실행 여부입니다. 기본값은 `false`이며 정규장에는 실행되지 않습니다. `REPORTS_AGGREGATED` 이후 PRE_OPEN 선택 유지보수에서 cycle lease·전역 heavy-job admission·job lock을 얻은 경우에만 최대 25개 instance를 처리합니다.
- 메타데이터 보존과 빈 보유 정리는 개장 readiness 시각 30분 전까지만 새로 시작합니다. 앞 단계가 늦어 cutoff를 넘겼으면 두 비필수 작업은 DB·JobRepository 호출 전에 건너뛰며, 메타데이터 보존이 cutoff를 넘긴 경우에도 권위 시뮬레이션 시각을 다시 확인해 빈 보유 정리를 새로 시작하지 않습니다. 이후 권리락·분할·상장 같은 필수 PRE_OPEN 변환을 계속하여 개장 준비 시간을 보호합니다.
- `stock.batch.metadata-retention.retention-real-days`: simulation 거래일이 아니라 metadata `END_TIME` 기준 현실 보존일입니다. 기본값은 30일, 허용 범위는 1~3650일입니다.
- `stock.batch.metadata-retention.instance-limit`, `max-executions-per-instance`, `max-steps-per-instance`: 한 회차의 후보·domain 객체 적재·transaction 상한입니다. 기본값은 각각 25/20/200, 코드 최대값은 100/50/500입니다. 실패·중단·최근 execution이 하나라도 섞인 JobInstance는 전체 제외하며, 애플리케이션이 직접 실행하는 metadata 후보·archive SQL은 10초 statement timeout을 사용합니다.
- `stock.batch.metadata-retention.purge-enabled`: 기본값은 `false`입니다. `true`이면 비어 있지 않은 `purge-job-names`가 필수이고, archive가 끝난 allow-list JobInstance만 Spring Batch 6 `JobRepository.deleteJobInstance`로 삭제합니다. 완료 metadata 삭제 후 같은 identifying parameter가 새 JobInstance가 될 수 있으므로 업무 멱등성이 증명되지 않은 job은 allow-list에 넣지 않습니다.
- metadata retention을 운영하려면 백엔드·배치 종료와 실행 중 native Job 0건을 확인한 유지보수 창에서 metadata 전용 SQL을 먼저 적용합니다. 적용 직후에도 archive와 purge를 모두 끈 상태로 시작하고, archive-only 관찰 후 보존기간과 allow-list를 승인합니다. metadata DB가 business DB와 같은 MySQL 호스트를 공유하므로 활성화 전후 주문/체결 TPS·p95/p99·commit·I/O를 동일 부하로 비교하며 TPS 95% 비회귀 게이트를 그대로 적용합니다.
- `stock.batch.holding-cleanup.enabled`: 0주/0예약 보유 row 유지보수 정리 job 활성화 여부
- `stock.batch.holding-cleanup.fixed-delay-ms`: 빈 보유 row 정리 job 실행 간격. 기본값은 300000ms입니다.
- `stock.batch.holding-cleanup.retention-simulation-days`: 마지막 갱신 이후 보존할 시뮬레이션 일수. 기본값은 1일입니다.
- `stock.batch.holding-cleanup.delete-limit`: 한 번에 삭제할 최대 row 수. 기본값은 1000건입니다.
- 빈 보유 정리는 개장 정합성의 필수 단계가 아닌 선택적 유지보수입니다. 설정·runtime control 비활성이나 명시적인 작업 실패는 기록한 뒤 PRE_OPEN 기업행사 변환을 계속하지만, 서비스 종료·락 경합처럼 실행 자체가 `SKIPPED`되면 phase를 진전시키지 않습니다.
- 자동 실행 중지/재개 상태는 `stock_batch_job_control.runtime_enabled` DB row가 기준입니다. row가 없으면 batch 서버나 stock-back이 최초 조회 시 `runtime_enabled=true`, `scheduler_configured=true`로 생성하고, batch 서버가 실행 전 자신의 실제 설정값을 `scheduler_configured`에 동기화합니다. 운영 중에는 stock-back이 stock-batch HTTP API를 호출하지 않고 같은 DB row를 직접 변경합니다.
- 체결 worker의 기본 1초 runtime-control refresh는 정상 상태에서 제어 PK SELECT만 실행하며 명시적 쓰기 트랜잭션이나 COMMIT을 만들지 않습니다. 최초 row 생성·설정값 동기화와 관리자의 상태 변경에만 쓰기가 발생합니다.
- stock-back의 수동 월급 지급, 종목 장마감 롤오버, 거래정지/서킷브레이크 미체결 정리 요청은 요청 당시 거래일·종목 epoch·실행 가능 시각을 가진 `stock_batch_job_signal` row로 저장됩니다. `BatchJobSignalScheduler`는 만료되지 않은 신호만 `FOR UPDATE SKIP LOCKED`로 claim하고 기존 `StockBatchJobLauncher`를 실행합니다.
- 종목 단위 rollover·미체결 정리는 stock-back이 종목을 `CLOSED`, `HALTED`, `CIRCUIT_BREAKER`로 먼저 전환한 뒤에만 요청합니다. 배치 signal 검증과 실제 rollover 서비스도 열린 종목을 각각 Job 시작 전과 cycle 생성 전에 재차 거부하므로, 잘못 생성된 신호나 내부 직접 호출이 정규장 열린 종목의 주문 원장을 스캔하지 않습니다. 이 검증은 수동 종목 제어 경로에만 적용되며 전체시장 장마감과 정규장 주문·체결에는 SQL을 추가하지 않습니다.
- `stock.batch.signal.fixed-delay-ms`: DB signal 큐 폴링 간격. 기본값은 5000ms(5초)입니다.
- `stock.batch.signal.lease-seconds`: 처리 중인 신호 claim의 lease입니다. 기본값은 180초이며 비정상 종료 후 만료된 claim만 다른 인스턴스가 회수합니다.
- `stock.batch.signal.heartbeat-interval-seconds`: claim된 신호가 동기식 대상 Job을 실행하는 동안 `stock_batch_job_signal`의 정확한 PK·claim token 한 행만 갱신하는 주기입니다. 기본값은 30초이고 `lease-seconds`보다 짧아야 합니다. 주문·체결·계좌·보유 원장을 읽지 않으며, 종료 상태 기록 전에 heartbeat를 중단해 완료된 신호의 lease가 다시 연장되지 않게 합니다. 일시적인 DB 갱신 실패 한 번으로 heartbeat를 영구 중단하지 않고 다음 주기에 재시도하며, 업무 종료 직전에 같은 claim을 한 번 더 갱신해 소유권을 확인합니다. 소유권이 이미 다른 서버로 넘어간 경우 stale token으로 완료·실패 상태를 덮어쓰지 않고 현재 poll만 종료합니다.
- `stock.batch.signal.retry-base-seconds` / `retry-max-seconds`: 일시적 lock·phase 미도달 신호의 지수 backoff 하한/상한입니다. 기본값은 2초/300초입니다.
- signal poll은 먼저 `stock_batch_job_signal` claim 인덱스의 비잠금 point-range 조회로 실행 대상 존재 여부만 확인합니다. 빈 큐에서는 `FOR UPDATE`, attempt UPDATE, job-lock을 전혀 실행하지 않으며, 최대 시도 lease의 dead-letter UPDATE도 기본 60초 주기로 분리합니다. 신호가 존재할 때만 기존 원자적 `FOR UPDATE SKIP LOCKED` claim을 실행합니다.
- signal 처리는 전체 active job 여부로 막지 않고 target job의 기존 job lock, symbol lock, transaction 정책에 맡깁니다. target job이 lock 점유나 실행 phase 미도달로 `SKIPPED`를 반환하면 `DEFERRED`와 `next_attempt_at`을 저장하고, 같은 poll에서 뒤 신호를 계속 처리해 선두 지연을 만들지 않습니다. 최대 시도 횟수를 넘으면 `DEAD_LETTER`로 종료합니다.
- `stock.batch.signal.chunk-limit`: 한 번의 폴링에서 처리할 최대 signal 수. 기본값은 20건, 허용 범위는 1~100건입니다. 과도한 설정으로 유지보수 executor가 여러 Job을 장시간 연속 실행해 정규장 DB 연결을 잠식하지 않도록 기동 시 검증합니다. 만료 lease의 dead-letter sweep도 한 트랜잭션당 100건으로 제한합니다.
- runtime 중지는 해당 job의 스케줄러 자동 실행만 건너뛰게 합니다. `/internal/stock-batch/v1/jobs/**` 수동 실행 API는 관리자 명시 실행으로 별도 허용합니다.
- `stock.batch.job-lock.ttl-seconds`: 배치 job DB 락 만료 시간. 서버 비정상 종료 후 영구 락을 막기 위한 값이며 기본값은 180초입니다. 여러 batch 서버가 동시에 떠 있는 운영에서는 가장 긴 job 예상 실행 시간보다 충분히 길게 잡아야 하며, heartbeat가 정상 갱신하므로 정상 실행 중인 긴 job은 계속 락을 연장합니다. 물리 실행마다 서버 owner 뒤에 별도 UUID token을 붙이므로 같은 JVM에서 만료 lock을 새 실행이 회수해도 이전 heartbeat·release가 새 lock을 연장하거나 삭제할 수 없습니다. 단발성 DB 갱신 오류 뒤 다음 heartbeat나 업무 종료 직전 최종 갱신이 성공하면 이전 오류를 회복된 것으로 처리하고, 실제 job/admission/cycle 소유권 상실만 실패로 남깁니다. 종료 직전 검증은 소형 제어행만 갱신하며 주문·체결 원장을 읽지 않습니다.
- `stock.batch.job-lock.heartbeat-interval-seconds`: 실행 중인 batch 서버가 자기 소유 DB 락의 `locked_until`을 연장하는 주기입니다. 기본값은 30초이며 `ttl-seconds`보다 충분히 짧게 둬야 정상 실행 중인 job을 다른 서버가 만료 락으로 가져가지 않습니다.
- `stock.batch.scheduler-pools.execution.pool-size`: 주문장/현재가 체결 job 전용 scheduler pool 크기. 기본값은 2, 허용 범위는 1~4입니다. 자동장 주문 생성이 오래 걸려도 체결 job이 실행 기회를 잃지 않도록 분리하되, 과도한 polling 동시성은 기동 시 차단합니다.
- `stock.batch.scheduler-pools.auto-market.pool-size`: 자동 참여자 주문 생성, 자동장 주문 만료, 상장주관사 주문 공급 전용 scheduler pool 크기. 값은 반드시 1이어야 합니다. 같은 주문/계좌/보유 테이블을 쓰는 주기 작업을 병렬 진입시키지 않습니다.
- `stock.batch.scheduler-pools.maintenance.pool-size`: 경량 유지보수 job scheduler pool 크기. 값은 반드시 1이어야 하며, 무거운 작업의 중첩으로 주문·체결 DB 연결을 압박하지 않도록 직렬 실행합니다.
- `stock.batch.scheduler-pools.post-close.pool-size`: 장마감 동결·정산·야간 후처리·개장 준비 coordinator 전용 scheduler pool 크기. 값은 **반드시 1**이어야 하며 다른 값이면 기동을 거부합니다. 거래량 증가 때 EOD phase 두 개가 동시에 주문·체결 원장과 DB connection을 압박하지 않도록 phase를 한 번에 하나만 실행합니다.
- `stock.batch.scheduler-pools.simulation-clock.pool-size`: 시뮬레이션 시간 heartbeat 전용 scheduler pool 크기. 값은 반드시 1이어야 합니다. 긴 배치 작업 때문에 시뮬레이션 시간이 늦게 누적되지 않도록 별도 분리합니다.
- `stock.batch.scheduler-pools.shutdown-await-seconds`: 전용 scheduler pool 종료 대기 시간. 기본값은 120초입니다.
- `stock.batch.jdbc.query-timeout-seconds`: 업무 DB용 `JdbcTemplate` statement query timeout입니다. 기본값은 30초이며 0 이하 값은 시작 시 거부합니다.
- `stock.batch.execution.scan-limit`: 한 번의 체결 job 실행에서 처리할 최대 체결 횟수입니다. 기본값은 300, 허용 범위는 1~5,000입니다.
- `stock.batch.execution.buy-candidate-scan-limit`: 주문장 매칭 1회에서 한 SQL로 비교할 매수·매도 상위 후보 수입니다. 기본값은 20, 허용 범위는 1~100입니다. 후보 탐색에서는 잠금을 잡지 않고, 선택된 주문 PK 2개만 오름차순으로 잠근 뒤 재검증합니다.
- `stock.batch.execution.symbol-chunk-limit`: 한 종목 lock을 잡고 연속 처리할 최대 체결 횟수입니다. 기본값은 5, 허용 범위는 1~50입니다.
- `stock.batch.execution.symbol-chunk-max-duration-ms`: 한 종목 lock을 유지할 최대 목표 시간입니다. 기본값은 500ms, 허용 범위는 1~1,000ms이며 횟수 또는 시간 제한에 먼저 도달하면 종목을 재등록합니다.
- `stock.batch.execution.ready-symbol-fallback-scan-limit`: Redis 복구용 DB fallback 한 회차의 symbol 상한입니다. 기본값은 8, 허용 범위는 1~100입니다.
- `stock.batch.order-book-execution.worker.*`: Redis ready-symbol 큐를 상시 소비하는 체결 worker 설정입니다. 기본 worker 수는 2, 허용 범위는 1~8이며 빈 큐 대기는 100ms입니다. 체결 성공 청크 뒤에는 기본 5ms(`match-yield-ms`) 동안 양보해 상장주관사 유동성 공급과 자동 주문 만료가 같은 종목 lock을 얻을 기회를 보장합니다. worker 수는 종목 수·DB pool·실측 TPS보다 먼저 늘리지 않습니다.
- 자동 참여자 주문·만료·상장주관사 공급은 한 실행에서 시뮬레이션 clock을 중복 조회하지 않습니다. 종목별 주문 만료와 상장주관사 주문은 이미 잠근 session fence가 반환한 `businessEffectiveAt`을 재사용하고, 자동 참여자 profile/symbol shard도 상위 run 시각을 전달받으므로 종목 수에 비례한 clock singleton 조회가 생기지 않습니다.
- 자동시장 ready-profile 큐는 활성 DB 스케줄의 profile 집합과 맞추며 삭제·비활성 profile을 한 번에 제거합니다. 실패 lease가 남은 profile은 1초 빈 재시도 대신 실제 lease 만료 시각까지 대기해 worker 슬롯과 DB 연결을 낭비하지 않습니다.
- coordinator 모드에서도 서버 기동 시와 기본 10분 주기의 profile 큐 정합화는 **정규장이고 실제 주문장이 OPEN일 때만** 경량 서비스로 직접 수행합니다. Redis 유실이나 claim 직후 JVM 종료로 빠진 profile을 다음 PRE_OPEN까지 방치하지 않기 위한 복구 경로이며, Spring Batch/JobRepository를 실행하지 않고 작은 profile·schedule·config 테이블만 읽습니다. `stock_order`·`stock_execution`을 스캔하지 않으므로 거래량에 비례하는 장중 부하를 만들지 않습니다.
- PRE_OPEN의 필수 자동시장 준비는 정규장 best-effort 복구와 다르게 fail-closed입니다. 전용 ready-profile zset을 Redis Lua 1회로 정확히 교체하고, 저장된 distinct profile 수가 DB schedule 결과와 일치해야 phase를 완료합니다. Redis 예외·null 응답·건수 불일치는 개장을 준비 완료로 넘기지 않으며, 이 strict 경로는 시장이 닫힌 PRE_OPEN에서만 실행되어 주문·체결 원장 SQL이나 정규장 lock/commit을 추가하지 않습니다.
- 자동 주문 run은 전체 종목 설정·최신 보고서·프로필 정책·regime을 읽기 전에 Redis ready-profile zset의 due 존재 여부를 먼저 확인합니다. due 프로필이 없으면 auto-market service의 업무 DB 조회는 0회이고, due가 확인된 뒤 실제 profile claim에 성공한 경우에만 프로필 정책과 주/보조 랜덤값을 읽습니다. Redis 확인 실패는 fail-closed로 처리합니다.
- `stock.batch.order-book-execution.fixed-delay-ms`: Redis 장애나 서버 재시작 중 누락된 체결 가능 종목을 DB에서 복구하는 저주기 fallback이며 기본값은 30000ms, 허용 범위는 10000~300000ms입니다. 정상 체결 지연은 신규 주문 after-commit Redis enqueue와 상시 worker가 담당하므로 이 값을 낮춰 5초 DB polling으로 되돌리지 않습니다.
- `stock.batch.execution-account-summary.enabled`: 장중 계좌 일일 체결 요약의 경량 bulk flush를 제어합니다. 체결 트랜잭션은 요약 DB 쓰기를 직접 기다리지 않고 after-commit에서 `(거래일, 계좌)` 메모리 누적값만 병합합니다. 같은 계좌의 체결이 수만 건이어도 pending 슬롯은 1개이며, 기본 flush는 30초·최대 5,000 account-day row를 최대 500행의 명시적 multi-row UPSERT로 저장합니다. 전체 flush는 한 업무 트랜잭션이라 중간 실패 시 부분 커밋 없이 현재 map 값과 재병합하고, 실패 중 새 체결도 유실하지 않습니다. 고유 계좌 슬롯 상한을 넘은 실패분은 `result=requeue-overflow` metric으로 기록하고 원본 체결은 지연·실패시키지 않습니다. 야간 보고서 Step은 `[T 00:00, T+1 00:00)` 체결 범위만 계좌별 집계해 매수/매도 gross·net, 수수료, 세금, 실현손익을 정확히 재구축합니다. pending row가 0이면 runtime-control 조회와 전역 DB job-lock 획득·해제까지 생략합니다. flush와 재구축은 같은 전역 job lock을 사용하며 락 획득·정확 재구축·해제를 별도 짧은 트랜잭션으로 처리하므로 야간 작업이 flush connection을 장시간 대기시키지 않습니다. 완료 일자의 늦은 누적값은 중복 UPSERT하지 않고 폐기 지표로 기록합니다.
- `stock.batch.execution-account-summary.flush-fixed-delay-ms`: 계좌 일일요약 flush 간격입니다. 기본값은 30000ms이며, 더 짧게 설정하면 체결 DB commit과 경쟁할 수 있으므로 코드 기본값은 30초 이상으로 검증합니다.
- `stock.batch.execution-account-summary.flush-batch-size`: 한 flush가 drain하는 고유 account-day row 상한입니다. 기본값과 허용 최대값은 5,000이며 이를 넘으면 서버 기동을 거부합니다.
- `stock.batch.execution-account-summary.max-pending-deltas`: 호환성을 위해 이름은 유지하지만 실제 의미는 체결 건수가 아니라 after-commit 파생요약의 고유 `(거래일, 계좌)` 슬롯 상한입니다. 기본값은 100,000, 허용 범위는 1~1,000,000입니다. 초과 슬롯은 metric으로 기록하고 원본 체결은 실패시키지 않으며, 환경값으로 무제한 메모리를 예약할 수 없습니다.
- 체결은 잠금 없는 교차 후보 조회를 business transaction 밖에서 실행하고, 성공한 경우에만 트랜잭션을 시작해 종목 session fence를 공유 잠금합니다. 이후 계좌·보유·주문 PK를 잠그고 후보를 재검증하므로 장마감 cutoff 정확성은 유지하면서, 가격 미교차·유동성 부족 probe가 빈 COMMIT·fence 조회·close barrier 대기를 추가하지 않습니다.
- 한 symbol의 체결 결과가 0건이면 동일한 `hasExecutablePair` 쿼리를 즉시 반복하거나 Redis에 다시 넣지 않습니다. 신규·정정 주문의 after-commit enqueue와 저주기 DB fallback이 다음 기회를 만들고, 1건 이상 체결된 chunk만 잔여 교차 쌍을 확인하므로 비유동 종목이 worker와 DB를 계속 점유하지 않습니다.
- 관리자 EOD overview는 화면이 열려 있을 때만 15초 주기로 cycle/metric/attempt/signal 제어행을 조회합니다. `stock_order`·`stock_execution`을 집계하지 않으며, cycle은 정렬 중심 `idx_stock_post_close_cycle_scope_date_status`와 상태 중심 `idx_stock_post_close_cycle_scope_status_date`, 최근 signal은 `idx_stock_batch_job_signal_cycle_id` 제어 인덱스 후보를 사용합니다. 실제 optimizer 선택은 운영 MySQL에서 확인하며, 이 인덱스들은 저빈도 제어 테이블에만 존재하므로 정규장 주문·체결 쓰기 증폭을 만들지 않습니다.
- 여러 시뮬레이션 거래일 동안 서버가 중단된 경우 누락 날짜는 한 coordinator poll당 하루만 `SKIPPED`로 기록합니다. 빈 장마감·정산을 날짜 수만큼 실행하지 않고, 원시 시각을 따라잡은 마지막 `SKIPPED` cycle만 다음 실제 개장일의 PREOPEN 기업행사·가격·regime·프로필 큐·readiness suffix를 수행합니다. 이 복구 판정은 제어 테이블만 사용하며 주문·체결 원장을 조회하지 않습니다.
- 주문 체결, 자동 주문 생성, 자동 주문 만료, 상장사 유동성 공급, 프로필 큐 정합화는 고빈도 `LightweightBatchTask`로 실행해 Spring Batch `BATCH_*` 실행 이력을 매회 생성하지 않습니다. 장마감·정산·기업 이벤트·현금 지급·일별 regime 생성은 native JobRepository 기록을 유지합니다.
- `stock.batch.execution.symbol-lock.type`: 동일 종목 중복 체결 방지 방식입니다. 기본값은 `redis`이며 테스트에서는 `none`을 사용합니다.
- `stock.batch.execution.symbol-lock.ttl-seconds`: Redis symbol lock TTL입니다. 기본값은 120초입니다. 정규장 한 번의 symbol chunk가 이 시간 안에 끝나도록 `symbol-chunk-limit`과 함께 조정합니다. 전체 장마감이 TTL보다 오래 걸려도 정확성은 Redis lease가 아니라 이미 `CLOSING/CLOSED`로 커밋된 DB session fence, 전역 Job lock, cycle lease, frozen cohort가 보장합니다. Redis 락은 중복 worker 진입을 줄이는 보조 수단이므로 TTL을 장마감 총시간에 맞춰 무제한 키우지 않습니다.
- `stock.batch.execution.deadlock-retry-max-attempts`: 주문장 매칭 1회 트랜잭션의 lock/deadlock 재시도 횟수입니다. 기본값은 3입니다.
- `stock.batch.execution.deadlock-retry-backoff-ms`: 주문장 매칭 deadlock 재시도 간 기본 backoff입니다. 기본값은 50ms이며 attempt 번호를 곱해 짧게 증가시킵니다.
- `stock.batch.execution.slow-symbol-log-threshold-ms`: 한 종목 체결 chunk가 이 값보다 오래 걸리면 `symbol`, `matchCount`, `elapsedMs`를 info log로 남깁니다. 기본값은 1000ms입니다.
- 파일 로그의 공통 루트는 `STOCK_LOG_ROOT`, 배치 전용 경로는 `STOCK_BATCH_LOG_DIR`, 실행 인스턴스 표시는 `STOCK_INSTANCE_ID`로 지정합니다. 파일 로그에는 PID·포트·인스턴스가 포함되고 테스트 프로필은 파일 로그를 기록하지 않습니다. 종목 lock 경합은 매회 WARN 대신 `stock.listing.auto.market.symbol.lock.skips`, `stock.auto.market.order.expiry.symbol.lock.skips` 카운터로 확인합니다.
- 자동 참여자 주문의 계획 대비 저장 탈락은 `/actuator/metrics`의 `stock.auto.market.order.decisions`, `stock.auto.market.order.planned`, `stock.auto.market.order.stored`, `stock.auto.market.order.dropped`로 확인합니다. `dropped`의 `reason`은 `side_not_selected`, `open_quantity_limit`, `invalid_price`, `insufficient_cash`, `insufficient_holding`, `buy_reservation_failed`, `sell_reservation_failed` 중 하나이며 계좌·종목·프로필은 지표 태그로 사용하지 않습니다. DB 저장 예외는 기존 `stock.auto.market.order.insert.failures`에서 별도로 확인합니다.
- `stock.batch.auto-market.profile-lock.type`: 자동 참여자 주문 생성 profile shard 중복 실행 방지 방식입니다. 기본값은 `redis`이며 테스트에서는 `none`을 사용합니다.
- `stock.batch.auto-market.symbol-selection.*`: 한 프로필 내부 참여자가 같은 종목으로 과도하게 몰리지 않도록 종목 선택 분산 강도, 참여자별 종목 affinity, 프로필별 최대 종목 점유율을 조정합니다.
- `spring.task.scheduling.shutdown.await-termination`: 서버 종료 시 실행 중인 `@Scheduled` 작업 완료를 기다릴지 여부. 기본값은 true로 둡니다.
- `spring.task.scheduling.shutdown.await-termination-period`: scheduler 작업 완료 대기 시간. 기본값은 120초입니다.
- `spring.lifecycle.timeout-per-shutdown-phase`: Spring Boot graceful shutdown phase 제한 시간. scheduler 대기 시간보다 길게 잡으며 기본값은 130초입니다.
- `stock.batch.shutdown.await-running-jobs-seconds`: `StockBatchJobRunner`가 종료 중 실행 중인 native Job과 경량 task 완료를 기다리는 시간. 기본값은 120초입니다.
- 종료가 시작되면 새 수동/스케줄 job은 `SKIPPED`로 거절하고, 이미 실행 중인 job은 위 timeout까지 완료를 기다립니다. 아직 시간이 오지 않은 다음 스케줄 job을 종료 전에 강제로 실행하지는 않습니다.
- 자동 참여자 운용 현금은 `stock_account_cash_flow`의 입금/회수 원장과 `stock_account.cash_balance`로 관리합니다. 주기 입금은 자동장 주문 생성과 분리되어 장 상태/종목 자동 알고리즘 상태와 무관하게 `ACTIVE` 계좌를 가진 enabled 자동 참여자 기준으로 실행됩니다. 참여자별-종목별 주문 활동 강도는 `stock_auto_participant_symbol_config`, 종목별 주·보조 압력 분포 편향과 최대 수량/TTL은 `stock_auto_market_config`에 저장한 값을 사용합니다. 자동 참여자의 주식 보유는 초기 지급이 아니라 주문장 매수 체결로만 생깁니다.
- `stock.batch.execution.fee-rate`: 체결 수수료율. 기본값 `0.0000`
- `stock.batch.execution.sell-tax-rate`: 매도 거래세율. 기본값 `0.0000`

KIS provider는 OAuth 접근토큰을 발급받은 뒤 `/uapi/domestic-stock/v1/quotations/inquire-price`를 호출하고, 응답의 현재가를 `stock_price`, `stock_price_tick`, Redis 최신가 캐시에 반영합니다.

## 내부 Job API 보호

`GET /internal/stock-batch/v1/system/status`는 상태 확인과 smoke check를 위해 열어둡니다.

`/internal/stock-batch/v1/jobs/**` 실행/제어 API는 `X-Internal-Token` 헤더가 `STOCK_BATCH_INTERNAL_TOKEN`과 일치해야 실행됩니다. 기본 `local-direct`는 `local-stock-batch-internal-token`을 사용하고 `20481` 포트로 뜹니다. `local`/`dev`도 `20481`, `test`는 `30481`, `prod`는 `10481`을 사용합니다. 빈 token 허용은 테스트/smoke 편의 profile에서만 켭니다.
`dev`/`prod` profile은 `STOCK_BATCH_INTERNAL_TOKEN`을 반드시 명시해야 하며, 빈 token 허용을 켜지 않습니다.

```bash
STOCK_BATCH_INTERNAL_TOKEN=change-me ./gradlew :stock-batch-service:bootRun
curl -X POST http://localhost:20481/internal/stock-batch/v1/jobs/order-book-execution/run \
  -H 'X-Internal-Token: change-me'
```

Cloud Gateway를 통할 때도 batch 서버의 내부 토큰 검증은 유지합니다. Gateway의 `/internal/stock-batch/v1/jobs/**` 경로는 `ZEROQ_GATEWAY_SHARED_SECRET` 기반 HMAC 인증을 먼저 요구하고, 통과한 요청에 `X-Internal-Token`을 주입해 `stock-batch-service`로 전달합니다.
`scripts/stock-gateway-h2-smoke.sh`는 auth-back, stock-back, stock-batch, cloud gateway를 H2 profile로 함께 띄워 gateway HMAC job route와 JWT 기반 stock 보호 API를 동시에 검증합니다.

Job 응답의 `data.status`는 `COMPLETED`, `SKIPPED`, `FAILED` 중 하나입니다. 같은 job이 이미 실행 중이면 `SKIPPED`로 응답하고, provider/DB 등 내부 실행 오류가 발생하면 `FAILED`와 `message`로 실패 사유를 반환합니다.

## 패키지 경계

- `batch/common/support`: job 실행 잠금, 실패 응답 변환, 공통 job 실행 계약
- `batch/<domain>/job`: 스케줄러/API가 실행할 도메인별 job 흐름
- `batch/<domain>/reader`: DB에서 처리 대상을 읽는 read-only 컴포넌트
- `batch/<domain>/processor`: provider 결과 검증, 순수 계산, command 변환
- `batch/<domain>/writer`: DB/Redis snapshot/outbox 등 한 종류의 반영 책임
- `marketdata`: 외부 시세 Provider client, 최신가 캐시 writer
- `execution`: 미체결 주문 조회와 가상 체결 판단
- `settlement`: 시뮬레이션일별 평가, 수익률, 랭킹 정산
- `scheduler`: 잡 트리거와 실행 주기 관리

## 설계 기준

- Spring Batch 6.x JDBC `JobRepository`를 별도 metadata schema로 사용합니다.
- 스케줄러는 실행 시점만 결정하고, 수동 API와 스케줄러는 모두 `StockBatchJobLauncher`를 통해 같은 job 컴포넌트를 실행합니다.
- job 실행 잠금은 `stock_batch_job_lock` DB 테이블을 통해 처리합니다. `StockBatchJobRunner`는 native Job을 `JobOperator`로 시작하고 실행 결과를 공통 응답으로 변환하며, 실제 `BATCH_JOB_EXECUTION`/`BATCH_STEP_EXECUTION` 기록과 재시작 판단은 Spring Batch `JobRepository`가 담당합니다.
- 주요 native Job/Step은 `market-close-rollover / market-close-snapshot-step`, `portfolio-settlement / validate-close-snapshot-step -> portfolio-settlement-step -> complete-portfolio-settlement-step`, `post-close-report-aggregation / 종목 보고서 -> 계좌 보고서 -> 계좌 일일요약 재구축 Step`, `auto-participant-cash-flow / auto-participant-cash-flow-step`, `auto-market-daily-regime-pre-create / auto-market-daily-regime-pre-create-step`, `market-open-readiness / market-open-readiness-step`입니다.
- `corporate-actions`의 기본 coordinator 경로는 operation별 flow를 사용합니다. `CASH`는 `cash-dividend-payment-step -> capital-increase-auto-subscription-step -> capital-increase-payment-step -> validate-corporate-cash-step`, `PREOPEN_SECURITY_TRANSFORMS`는 `apply-ex-rights-step -> capital-increase-listing-step -> free-share-listing-step -> stock-split-step -> delisting-step -> validate-preopen-security-transform-step`입니다. 호환용 `ALL` operation만 기존 `apply-due-corporate-actions-step`을 유지합니다. 이 Step metadata는 00시 이후 단일 heavy-job admission 안에서만 기록하며 정규장 주문·체결 worker에는 추가되지 않습니다.
- `portfolio-settlement-step`은 `JdbcPagingItemReader -> PortfolioSnapshotProcessor -> chunk ItemWriter` 구조이며 `stock.batch.settlement.chunk-size`(기본 200, 허용 1~2,000)를 business transaction 경계로 사용합니다. 범위를 벗어나면 장시간 정산 트랜잭션을 막기 위해 서버 기동을 거부합니다. MySQL Writer는 이 chunk를 최대 500행 multi-row UPSERT 문장으로 저장합니다. Reader의 보유 집계는 계좌별 파생 집계로 페이징 키와 분리해 다음 페이지 조건의 `id`가 모호해지지 않게 합니다. 정산 lifecycle은 열린 시장을 먼저 거부하므로 수동/내부 우회 호출도 정규장에 frozen-input count 쿼리를 실행하지 않습니다.
- 야간 보고서의 outer tasklet Step은 resourceless transaction을 사용하고 실제 종목/계좌 집계만 짧은 business `REQUIRES_NEW`로 실행합니다. 종목 순회 중 business connection을 유휴 상태로 점유하지 않으며, 매 청크의 마지막 symbol을 Spring Batch `ExecutionContext`에 저장합니다. 실패 후에는 마지막 완료 symbol 뒤에서 이어가고, 컨텍스트 커밋 직전 장애로 한 청크가 반복되어도 해당 symbol 결과만 원자적으로 교체하므로 이미 완료된 거래일 전체 결과를 삭제·재집계하지 않습니다.
- 나머지 command Tasklet은 서비스가 자체 업무 트랜잭션 또는 이벤트별 `REQUIRES_NEW` 경계를 소유합니다. tasklet 외부에서 같은 business transaction을 중첩해 연결·잠금을 전체 반복 시간 동안 보유하지 않습니다.
- 비정상 종료 후 open 상태로 남은 native execution은 새 실행 노드가 해당 job의 `stock_batch_job_lock`을 획득한 경우에만 `FAILED`로 전환합니다. 이후 동일 identifying parameter로 재실행하면 완료된 Step은 건너뛰고 실패·중단 Step부터 이어갑니다. 시작 시 모든 `BATCH_*` row를 일괄 변경하는 전역 복구는 사용하지 않습니다.
- `marketdata`, `settlement`, `marketclose`는 batch 문서 기준에 맞춰 reader/processor/writer 또는 writer 단위로 책임을 분리합니다.
- 시세는 모든 종목을 무조건 갱신하지 않고 관심 종목, 보유 종목, 미체결 주문이 있는 종목을 우선 갱신합니다.
- 시세 provider는 `MarketPriceProvider`로 분리하고, 실제 외부 API 연동은 provider 구현 교체로 처리합니다.
- 외부 provider 장애는 해당 종목 가격 갱신만 건너뛰고 나머지 종목 처리를 계속합니다.
- 유실되면 안 되는 주문/체결 결과는 Pub/Sub이 아니라 DB 원장에 기록합니다.
- 매도 체결은 `stock_holding.quantity`와 `reserved_quantity`를 함께 차감해 미체결 매도 예약과 실제 보유 원장을 맞춥니다.
- 체결 중에는 0주가 된 `stock_holding`을 즉시 삭제하지 않습니다. 주문장 체결의 lock/write 비용을 줄이기 위해 `holding-cleanup` 유지보수 job이 시뮬레이션 시간 기준 보존 기간이 지난 빈 row만 제한 건수로 삭제합니다.
- batch 서버는 주문장 체결 job만 운영합니다. 현재가 기준 자동 체결 job은 제거되었습니다.
- 유상증자 기업 이벤트는 주주배정과 일반공모만 corporate action job이 처리합니다. 주주배정은 권리락일 전 마지막 완료 장마감 snapshot의 권리부종가와 당시 발행주식수로 가격을 확정하고 계좌별 배정 entitlement를 만듭니다. 권리부종가가 발행가보다 높을 때만 희석 산식을 적용해 1원 미만을 절사하고, 그렇지 않으면 권리부종가를 유지합니다. 일반공모는 권리락 없이 전체 잔여수량을 공유합니다. corporate action job은 `symbol is null`인 전체 장마감 완료만 선행조건으로 인정합니다. 같은 날 현금배당 지급과 자동청약이 겹치면 배당을 먼저 지급하고, 장 마감 후 자동참여자는 일반 주문 프로필과 분리된 이벤트 프로필 정책으로 청약합니다. 납입일에는 미청약 주주 권리를 만료하고 action을 `PAID`로 전이합니다. 신주상장일에는 예정 발행수가 아니라 실제 `SUBSCRIBED` 합계만큼 `issued_shares`, `tradable_shares`와 계좌 보유수량·평균단가를 반영합니다.
- 액면분할 기업 이벤트는 효력일에 `issued_shares`, `tradable_shares`, 보유수량, 예약수량을 배율만큼 늘리고 현재가, 전일종가, 평균단가를 같은 배율로 나눕니다. 효력일에 미체결 주문이 있으면 가격/수량 기준이 꼬이지 않도록 적용을 대기합니다.
- 현금배당 기업 이벤트는 배당락일에 현재 보유수량 기준으로 `stock_corporate_action_entitlement` 지급 원장을 만듭니다. 지급일에는 해당 원장을 기준으로 `stock_account.cash_balance`를 증가시키고 중복 지급을 막기 위해 지급 원장을 `PAID`로 전이합니다. 현금배당 자체는 `stock_price`, `stock_price_tick`을 강제로 조정하지 않습니다.
- 무상증자와 주식배당 기업 이벤트는 배당락일 전 마지막 완료 장마감 snapshot의 권리부종가와 당시 발행주식수로 1원 미만을 절사한 이론권리락가격을 확정하고 같은 snapshot 보유수량 기준으로 신주 entitlement를 만듭니다. 신주상장일에는 `issued_shares`, `tradable_shares`, 보유수량을 늘리고 평균단가를 낮춘 뒤 entitlement를 `PAID`로 전이합니다.
- 체결 수수료와 매도 거래세는 체결 단위로 계산해 `stock_execution`에 `fee_amount`, `tax_amount`, `net_amount`, `realized_profit`으로 기록합니다. 매수 평균단가는 수수료 포함 원가 기준입니다.
- 자동장 job은 자동 참여자 주문을 실제 `stock_order` 원장에 공급합니다. 내부 주문장 체결은 별도 `order-book-execution` job이 처리하므로, 브라우저 localStorage나 프론트 전용 가짜 주문 상태에 의존하지 않습니다.
- 자동장 job은 최신 `stock_instrument_report_event`의 점수를 읽어 참여자별 성향과 섞습니다. 참여자 성향은 계속 주된 기준이고, 보고서는 관리자가 부여한 종목별 시장 해석 신호입니다.
- 주문장 시장가 주문은 반대편 지정가 호가가 있을 때만 체결합니다. 양쪽 모두 시장가인 주문은 기준 가격이 없기 때문에 체결 대상에서 제외합니다.
- 내부 주문장 모드는 자전거래 방지를 위해 같은 사용자끼리의 매수/매도 주문은 매칭하지 않습니다.
- 시뮬레이션 장마감은 먼저 시장 상태를 `CLOSED`로 내려 주문/체결/자동주문을 차단하고, 장마감 후처리인 기준가 롤오버와 포트폴리오 정산이 실제 완료된 뒤에만 다음 일자/다음 장으로 이동할 수 있습니다. 프로젝트 하루는 batch 서버 heartbeat 기준 현실 2시간이며, 서버가 꺼져 heartbeat가 멈추면 시뮬레이션 시간도 마지막 heartbeat 시점에서 멈춥니다. 운영 점검이나 smoke에서는 `POST /internal/stock-batch/v1/jobs/market-close/rollover`, `POST /internal/stock-batch/v1/jobs/portfolio-settlement/run` 수동 job API를 사용합니다.
