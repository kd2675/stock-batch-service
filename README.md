# stock-batch-service

주식 모의투자 서비스의 배치/워커 서버입니다.

## 역할

- 외부 주식 API에서 필요한 종목 시세 수집
- Redis 최신가 캐시 갱신
- 가격 변경 이벤트 발행
- 미체결 주문 체결 조건 검사
- 시뮬레이션일별 평가금액, 수익률, 랭킹 정산

## 현재 API

- `GET /internal/stock-batch/v1/system/status`
- `POST /internal/stock-batch/v1/jobs/market-data/refresh`
- `POST /internal/stock-batch/v1/jobs/virtual-price-execution/run`
- `POST /internal/stock-batch/v1/jobs/order-book-execution/run`
- `POST /internal/stock-batch/v1/jobs/auto-participant-cash-flow/run`
- `GET /internal/stock-batch/v1/jobs/auto-participant-cash-flow/status`
- `PATCH /internal/stock-batch/v1/jobs/auto-participant-cash-flow/status`
- `GET /internal/stock-batch/v1/jobs/runtime-controls`
- `PATCH /internal/stock-batch/v1/jobs/runtime-controls/{jobName}`
- `POST /internal/stock-batch/v1/jobs/auto-market/run`
- `POST /internal/stock-batch/v1/jobs/auto-market-order-expiry/run`
- `POST /internal/stock-batch/v1/jobs/listing-auto-market/run`
- `POST /internal/stock-batch/v1/jobs/portfolio-settlement/run`
- `POST /internal/stock-batch/v1/jobs/market-close/rollover`
- `POST /internal/stock-batch/v1/jobs/corporate-actions/run`

## 현재 잡

- `MarketDataRefreshScheduler`: 관심/보유/미체결 종목 가격 갱신, Redis 최신가/채널 발행
- `AutoParticipantCashFlowScheduler`: 자동 참여자 주기 입금을 `stock_account_cash_flow`와 `stock_account.cash_balance`에 반영
- `AutoMarketScheduler`: 자동 참여자 주문 생성, 자동장 미체결 주문 만료, 상장주관사 주문 공급 job을 실행
- `MarketPriceProvider`: 실제 시세 provider 교체 지점. 현재 기본값은 `stock.batch.market-data.provider=mock`
- `KisMarketPriceProvider`: `stock.batch.market-data.provider=kis`일 때 KIS OpenAPI 국내주식 현재가 시세를 호출
- `VirtualPriceExecutionScheduler`: `market_type=VIRTUAL_PRICE` 미체결 주문을 현재가 기준으로 체결
- `OrderBookExecutionScheduler`: `market_type=ORDER_BOOK` 사용자 매수/매도 주문을 가격 우선, 시간 우선으로 매칭
- `InternalOrderBookExecutionService`: 주문장 체결 엔진
  - 지정가끼리는 교차된 호가를 체결합니다.
  - 시장가 매수는 최우선 지정가 매도 호가와, 시장가 매도는 최우선 지정가 매수 호가와 체결합니다.
  - 양쪽이 모두 시장가이면 가격 기준이 없으므로 체결하지 않고 다음 스캔을 기다립니다.
- `PortfolioSettlementScheduler`: 시뮬레이션일별 자산/수익률 스냅샷 정산
- `MarketCloseRolloverService`: 장마감 종가를 다음 장 가격제한폭 기준가로 넘기기 위해 `stock_price.current_price`를 `previous_close`로 복사
- `HoldingCleanupScheduler`: 체결 hot path에서 즉시 삭제하지 않은 0주/0예약 보유 row를 보존 기간 이후 별도 유지보수 job으로 정리

## 실행과 검증

아래 명령은 `zeroq-common` 루트에서 실행합니다.

```bash
./gradlew :stock-batch-service:bootRun
./gradlew :stock-batch-service:bootRun --args='--spring.profiles.active=local'
./gradlew :stock-batch-service:bootRun --args='--spring.profiles.active=local-direct'
./gradlew :stock-batch-service:compileJava
./gradlew :stock-batch-service:test
scripts/stock-smoke.sh
STOCK_SMOKE_RUN_BATCH_JOBS=true scripts/stock-smoke.sh
STOCK_BATCH_INTERNAL_TOKEN=<token> STOCK_SMOKE_RUN_BATCH_JOBS=true scripts/stock-smoke.sh
ZEROQ_GATEWAY_SHARED_SECRET=<secret> STOCK_BATCH_INTERNAL_TOKEN=<token> STOCK_SMOKE_RUN_GATEWAY_BATCH_JOBS=true scripts/stock-smoke.sh
scripts/stock-gateway-h2-smoke.sh
```

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
- DDL: `src/main/resources/db/ddl/stock_all.sql`
- Batch metadata schema: `STOCK_BATCH_METADATA`
- Batch metadata DDL:
  - MySQL: `src/main/resources/db/schema/batch-metadata-mysql.sql`
  - H2/test: `src/main/resources/db/schema/batch-metadata-h2.sql`
- `local`/`dev` 기본값은 다른 백엔드 서비스와 맞춰 원격 개발 MySQL `kimd0.iptime.org:23306`과 Redis `kimd0.iptime.org:26379`입니다.
- `local`/`dev` 접속값은 기존 백엔드 프로젝트처럼 `application-local.yml`, `application-dev.yml`에 직접 둡니다.
- `prod`는 DB와 Redis 값을 환경 변수로 명시 주입합니다.
- `prod`의 `STOCK_DB_URL`, `STOCK_BATCH_DB_URL`은 query string 없는 기본 JDBC URL로 넣습니다. 공통 JDBC 옵션은 설정 파일에서 `connectTimeout=5000`, `socketTimeout=30000`, `tcpKeepAlive=true`를 기본으로 붙입니다.
- batch는 business DB용 `spring.datasource`와 Spring Batch metadata용 `stock.batch.repository.datasource`를 분리합니다. business 원장은 `STOCK_SERVICE`, `JobRepository` metadata는 `STOCK_BATCH_METADATA`를 사용합니다.
- batch 운영 제어 상태와 수동 실행 요청은 물리적으로 분리된 서버 간에도 공유되도록 `STOCK_SERVICE`의 `stock_batch_job_control`, `stock_batch_job_lock`, `stock_batch_job_signal` 테이블을 기준으로 합니다.
- `stock_batch_job_control`은 스케줄러 자동 실행 runtime ON/OFF 상태를 job별로 저장합니다. `runtime_enabled`는 stock-back/어드민이 바꾸는 운영 중지 값이고, `scheduler_configured`는 stock-batch가 실제 서버 설정값을 동기화한 값입니다. `stock_batch_job_lock`은 같은 job의 중복 실행을 DB 락으로 막습니다. `stock_batch_job_signal`은 stock-back이 적재한 수동 실행/후처리 요청을 batch가 폴링해 처리하는 비동기 신호 큐입니다.
- 현재 custom scheduler job은 Spring Batch metadata를 고빈도 실행 이력 ledger로 사용합니다. `businessDate`, `jobMode`, `runId`를 identifying parameter로 기록해 같은 업무일/모드의 반복 실행도 서로 다른 `JobInstance`가 되게 합니다. 재시작 가능한 chunk job을 새로 만들 때는 `runId` 방식으로 우회하지 말고 해당 job의 restart contract를 별도로 설계합니다.
- Hikari 풀은 local/dev 기본 30개이며, prod는 `STOCK_DB_MAX_POOL_SIZE`, `STOCK_DB_CONNECTION_TIMEOUT`, `STOCK_DB_MAX_LIFETIME`, `STOCK_DB_KEEPALIVE_TIME`로 조정합니다.
- Batch metadata Hikari 풀은 local/dev 기본 12개이며 prod는 `STOCK_BATCH_DB_URL`, `STOCK_BATCH_DB_USERNAME`, `STOCK_BATCH_DB_PASSWORD`, `STOCK_BATCH_DB_MAX_POOL_SIZE` 계열 환경 변수로 조정합니다.
- 배치 업무 SQL은 `stock.batch.jdbc.query-timeout-seconds`로 statement query timeout을 적용합니다. 기본값은 30초이며 `STOCK_BATCH_JDBC_QUERY_TIMEOUT_SECONDS`로 조정합니다.
- DDL은 schema와 제약만 생성합니다. 기본 종목, 최초 가격, 자동 참여자는 seed하지 않으며 관리자 API 또는 smoke/test 데이터에서 명시적으로 등록합니다.
- Redis key: `stock:price:{symbol}`. 값은 현재 단일 가격 문자열이며 `stock-back-service` 시장 가격 API가 우선 조회합니다. TTL 기본값은 60초이며 `STOCK_PRICE_CACHE_TTL_SECONDS`로 조정합니다.
- Redis channel: `stock.price.{symbol}`
- Redis에는 최신가 문자열과 pub/sub 메시지만 다루므로 `StringRedisTemplate` 기반 설정을 사용합니다. JSON Redis serializer는 현재 Spring Data Redis 4.x에서 removal deprecated 경고가 있어 사용하지 않습니다.
- 가격 수집 이력은 `stock_price_tick`에 append-only로 저장하며, `stock-back-service`의 `/api/stock/v1/markets/prices/{symbol}/ticks` API가 최근 이력을 조회합니다.
- 정산 평가는 DB 현재가를 우선 사용하되, 내부 주문장 체결처럼 아직 `stock_price`가 없는 보유 종목은 보유 평단가로 fallback합니다.
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
- `stock.batch.virtual-price-execution.enabled`: 현재가 기준 체결 job 활성화 여부
- `stock.batch.order-book-execution.enabled`: 주문장 체결 job 활성화 여부
- `stock.batch.corporate-actions.enabled`: 기업 이벤트 반영 job 활성화 여부
- `stock.batch.auto-market.enabled`: 자동 참여자 주문 생성 job 활성화 여부
- `stock.batch.auto-market.fixed-rate-ms`: 자동장 주문 생성 dispatch 주기입니다. 기본값은 5000ms이며, 이전 회차가 실행 중이어도 다음 회차를 전용 dispatcher에 제출합니다.
- `stock.batch.auto-market.fixed-delay-ms`: 이전 자동장 주문 생성 주기 설정입니다. 현재 dispatch 주기는 `fixed-rate-ms`를 사용합니다.
- `stock.batch.auto-market.daily-regime.enabled`: 자동장 일일 방향/자산 선호 pre-create job 활성화 여부
- `stock.batch.auto-market.daily-regime.fixed-delay-ms`: 장 시작 전 일일 방향/자산 선호 pre-create 검사 주기
- `stock.batch.auto-market.daily-regime.pre-create-before-minutes`: 시뮬레이션 장 시작 몇 분 전부터 다음 거래일 방향/자산 선호를 미리 생성할지 결정합니다. 기본값은 30분입니다.
- `stock.batch.auto-market.generation-participant-chunk-size`: 한 트랜잭션에서 주문 생성까지 처리할 자동 참여자 수입니다. 기본값은 25입니다.
- `stock.batch.auto-market.generation-profile-worker-count`: 한 auto-market run이 Redis ready profile queue에서 claim할 profile type 수입니다. 기본값은 9입니다. 전체 실행 slot이 부족하면 남은 profile은 claim하지 않고 다음 run에서 처리합니다.
- `stock.batch.auto-market.profile-queue.reconcile-fixed-delay-ms`: Redis ready profile queue reconcile 주기입니다. 기본값은 600000ms(10분)이며, 서버 시작 시에는 별도로 1회 reconcile을 수행합니다. 수동 복구는 `POST /internal/stock-batch/v1/jobs/auto-market-profile-queue/reconcile` endpoint를 사용합니다.
- `stock.batch.auto-market.generation-lease-seconds`: 주문 생성 대상으로 claim한 참여자-종목 스케줄의 lease 시간입니다. 주문 생성 실패 시 lease 만료 후 재시도할 수 있게 둡니다.
- `stock.batch.auto-market.generation-due-limit-per-symbol`: 한 회차에서 종목별로 조회할 주문 생성 대상 최대 수입니다. 기본값은 100입니다.
- `stock.batch.auto-market.deadlock-retry-max-attempts` / `deadlock-retry-backoff-ms`: 자동장 주문 생성 중 계좌/보유 예약 update에서 deadlock이 발생했을 때 같은 chunk 트랜잭션을 짧게 재시도하는 횟수와 backoff입니다.
- `stock.batch.auto-market.thread-pool.core-size` / `max-size` / `queue-capacity`: 자동장 주문 생성 profile shard를 처리하는 execution thread pool입니다. 기본값은 12/12/0이며, 전체 profile 작업 동시 실행 상한도 `max-size`와 같습니다. run thread는 executor 포화 시 profile 작업을 직접 실행하지 않고, 실행 slot을 확보한 뒤에만 Redis profile을 claim합니다.
- `stock.batch.auto-market.run-dispatcher.thread-pool.core-size` / `max-size` / `queue-capacity`: auto-market run 단위를 병렬 제출하는 dispatcher pool입니다. 기본값은 3/3/0이며, 4번째 동시 회차는 큐에 쌓지 않고 스킵합니다.
- `stock.batch.auto-market-order-expiry.enabled`: 자동장이 낸 미체결 주문 만료 job 활성화 여부
- `stock.batch.auto-market-order-expiry.fixed-delay-ms`: 자동장 미체결 주문 만료 검사 주기
- `stock.batch.auto-market-order-expiry.expiry-chunk-limit`: 한 회차에서 취소할 자동장 만료 주문 후보 최대 수
- `stock.batch.listing-auto-market.enabled`: 상장주관사 자동계정 주문 공급 job 활성화 여부
- `stock.batch.listing-auto-market.fixed-delay-ms`: 상장주관사 자동계정 주문 공급 주기
- `stock.batch.auto-participant-cash-flow.enabled`: 자동 참여자 주기 입금 job 활성화 여부
- `stock.batch.auto-participant-cash-flow.fixed-delay-ms`: 자동 참여자 주기 입금 검사 주기. 기본값은 300000ms(5분)입니다. 지급 여부는 시뮬레이션 시간 기준으로 판단하지만, 이 값은 실제 서버 시간이 기준인 polling 간격입니다. 초 단위 주기 입금을 즉시성 있게 테스트해야 할 때만 환경값으로 더 낮춥니다.
- `stock.batch.market-close.enabled`: 장 마감 기준가 롤오버 job 활성화 여부
- `stock.batch.market-close.poll-fixed-delay-ms`: 시뮬레이션 날짜 변경 감지 주기. 기본값은 5000ms이며, `stock_simulation_clock` 기준 날짜가 바뀔 때 장마감과 정산을 실행합니다.
- 장마감 후처리는 미체결 정리, 보유/종목 일일 스냅샷, 종가 rollover, 포트폴리오 정산 완료 여부로 판단합니다. 장 상태는 장마감 즉시 `CLOSED`로 내려 주문/체결을 막고, 후처리 완료 전에는 다음 일자 시작과 다음 장 시작 이동을 차단합니다.
- `stock.batch.settlement.enabled`: 포트폴리오 정산 job 활성화 여부
- `stock.batch.holding-cleanup.enabled`: 0주/0예약 보유 row 유지보수 정리 job 활성화 여부
- `stock.batch.holding-cleanup.fixed-delay-ms`: 빈 보유 row 정리 job 실행 간격. 기본값은 300000ms입니다.
- `stock.batch.holding-cleanup.retention-simulation-days`: 마지막 갱신 이후 보존할 시뮬레이션 일수. 기본값은 1일입니다.
- `stock.batch.holding-cleanup.delete-limit`: 한 번에 삭제할 최대 row 수. 기본값은 1000건입니다.
- 자동 실행 중지/재개 상태는 `stock_batch_job_control.runtime_enabled` DB row가 기준입니다. row가 없으면 batch 서버나 stock-back이 최초 조회 시 `runtime_enabled=true`, `scheduler_configured=true`로 생성하고, batch 서버가 실행 전 자신의 실제 설정값을 `scheduler_configured`에 동기화합니다. 운영 중에는 stock-back이 stock-batch HTTP API를 호출하지 않고 같은 DB row를 직접 변경합니다.
- stock-back의 수동 월급 지급, 종목 장마감 롤오버, 거래정지/서킷브레이크 미체결 정리 요청은 `stock_batch_job_signal.status='PENDING'` row로 저장되고, `BatchJobSignalScheduler`가 `PROCESSING`으로 claim한 뒤 기존 `StockBatchJobLauncher`를 실행합니다.
- `stock.batch.signal.fixed-delay-ms`: DB signal 큐 폴링 간격. 기본값은 5000ms(5초)입니다.
- `stock.batch.signal.chunk-limit`: 한 번의 폴링에서 처리할 최대 signal 수. 기본값은 20건입니다.
- runtime 중지는 해당 job의 스케줄러 자동 실행만 건너뛰게 합니다. `/internal/stock-batch/v1/jobs/**` 수동 실행 API는 관리자 명시 실행으로 별도 허용합니다.
- `stock.batch.job-lock.ttl-seconds`: 배치 job DB 락 만료 시간. 서버 비정상 종료 후 영구 락을 막기 위한 값이며 기본값은 180초입니다. 여러 batch 서버가 동시에 떠 있는 운영에서는 가장 긴 job 예상 실행 시간보다 충분히 길게 잡아야 하며, heartbeat가 정상 갱신하므로 정상 실행 중인 긴 job은 계속 락을 연장합니다.
- `stock.batch.job-lock.heartbeat-interval-seconds`: 실행 중인 batch 서버가 자기 소유 DB 락의 `locked_until`을 연장하는 주기입니다. 기본값은 30초이며 `ttl-seconds`보다 충분히 짧게 둬야 정상 실행 중인 job을 다른 서버가 만료 락으로 가져가지 않습니다.
- `stock.batch.scheduler-pools.execution.pool-size`: 주문장/현재가 체결 job 전용 scheduler pool 크기. 기본값은 2입니다. 자동장 주문 생성이 오래 걸려도 체결 job이 실행 기회를 잃지 않도록 분리합니다.
- `stock.batch.scheduler-pools.auto-market.pool-size`: 자동 참여자 주문 생성, 자동장 주문 만료, 상장주관사 주문 공급 전용 scheduler pool 크기. 기본값은 1입니다. 같은 주문/계좌/보유 테이블을 쓰므로 기본은 단일 실행을 유지합니다.
- `stock.batch.scheduler-pools.maintenance.pool-size`: 시세 갱신, 기업 이벤트, 월급 지급, 장마감 감지 등 유지보수성 job scheduler pool 크기. 기본값은 2입니다.
- `stock.batch.scheduler-pools.simulation-clock.pool-size`: 시뮬레이션 시간 heartbeat 전용 scheduler pool 크기. 기본값은 1입니다. 긴 배치 작업 때문에 시뮬레이션 시간이 늦게 누적되지 않도록 별도 분리합니다.
- `stock.batch.scheduler-pools.shutdown-await-seconds`: 전용 scheduler pool 종료 대기 시간. 기본값은 120초입니다.
- `stock.batch.jdbc.query-timeout-seconds`: 업무 DB용 `JdbcTemplate` statement query timeout입니다. 기본값은 30초이며 0 이하 값은 시작 시 거부합니다.
- `stock.batch.execution.scan-limit`: 한 번의 체결 job 실행에서 처리할 최대 체결 횟수입니다. 기본값은 300입니다.
- `stock.batch.execution.buy-candidate-scan-limit`: 주문장 매칭 1회에서 잠글 매수 후보 수입니다. 기본값은 20입니다. `EXISTS ... FOR UPDATE`로 매수/매도 범위를 한 번에 잠그지 않고, 매수 후보를 짧게 잠근 뒤 최우선 매도를 별도로 찾습니다.
- `stock.batch.execution.symbol-chunk-limit`: 한 종목 lock을 잡고 연속 처리할 최대 체결 횟수입니다. 기본값은 50입니다. lock을 오래 점유하지 않도록 `scan-limit`보다 작게 둡니다.
- `stock.batch.order-book-execution.fixed-rate-ms`: 주문장 체결 run dispatch 주기입니다. 기본값은 5000ms이며, 이전 체결 run이 실행 중이어도 다음 run을 전용 dispatcher에 제출합니다.
- `stock.batch.order-book-execution.run-dispatcher.thread-pool.core-size` / `max-size` / `queue-capacity`: 주문장 체결 run 단위를 병렬 제출하는 dispatcher pool입니다. 기본값은 3/3/0이며, 4번째 동시 회차는 큐에 쌓지 않고 스킵합니다.
- `stock.batch.execution.symbol-lock.type`: 동일 종목 중복 체결 방지 방식입니다. 기본값은 `redis`이며 테스트에서는 `none`을 사용합니다.
- `stock.batch.execution.symbol-lock.ttl-seconds`: Redis symbol lock TTL입니다. 기본값은 120초입니다. 한 번의 symbol chunk가 이 시간 안에 끝나도록 `symbol-chunk-limit`과 함께 조정합니다.
- `stock.batch.execution.deadlock-retry-max-attempts`: 주문장 매칭 1회 트랜잭션의 lock/deadlock 재시도 횟수입니다. 기본값은 3입니다.
- `stock.batch.execution.deadlock-retry-backoff-ms`: 주문장 매칭 deadlock 재시도 간 기본 backoff입니다. 기본값은 50ms이며 attempt 번호를 곱해 짧게 증가시킵니다.
- `stock.batch.execution.slow-symbol-log-threshold-ms`: 한 종목 체결 chunk가 이 값보다 오래 걸리면 `symbol`, `matchCount`, `elapsedMs`를 info log로 남깁니다. 기본값은 1000ms입니다.
- `stock.batch.auto-market.profile-lock.type`: 자동 참여자 주문 생성 profile shard 중복 실행 방지 방식입니다. 기본값은 `redis`이며 테스트에서는 `none`을 사용합니다.
- `stock.batch.auto-market.symbol-selection.*`: 한 프로필 내부 참여자가 같은 종목으로 과도하게 몰리지 않도록 종목 선택 분산 강도, 참여자별 종목 affinity, 프로필별 최대 종목 점유율을 조정합니다.
- `spring.task.scheduling.shutdown.await-termination`: 서버 종료 시 실행 중인 `@Scheduled` 작업 완료를 기다릴지 여부. 기본값은 true로 둡니다.
- `spring.task.scheduling.shutdown.await-termination-period`: scheduler 작업 완료 대기 시간. 기본값은 120초입니다.
- `spring.lifecycle.timeout-per-shutdown-phase`: Spring Boot graceful shutdown phase 제한 시간. scheduler 대기 시간보다 길게 잡으며 기본값은 130초입니다.
- `stock.batch.shutdown.await-running-jobs-seconds`: `StockBatchJobRunner`가 종료 중 실행 중인 custom job 완료를 기다리는 시간. 기본값은 120초입니다.
- 종료가 시작되면 새 수동/스케줄 job은 `SKIPPED`로 거절하고, 이미 실행 중인 job은 위 timeout까지 완료를 기다립니다. 아직 시간이 오지 않은 다음 스케줄 job을 종료 전에 강제로 실행하지는 않습니다.
- 자동 참여자 운용 현금은 `stock_account_cash_flow`의 입금/회수 원장과 `stock_account.cash_balance`로 관리합니다. 주기 입금은 자동장 주문 생성과 분리되어 장 상태/종목 자동 알고리즘 상태와 무관하게 `ACTIVE` 계좌를 가진 enabled 자동 참여자 기준으로 실행됩니다. 참여자별-종목별 강도는 `stock_auto_participant_symbol_config`, 종목별 최대 수량/TTL은 `stock_auto_market_config`에 저장한 값을 사용합니다. 자동 참여자의 주식 보유는 초기 지급이 아니라 주문장 매수 체결로만 생깁니다.
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
- job 실행 잠금은 `stock_batch_job_lock` DB 테이블을 통해 처리하며, `COMPLETED`/`SKIPPED`/`FAILED` 응답 변환과 `BATCH_JOB_EXECUTION`/`BATCH_STEP_EXECUTION` 기록은 `StockBatchJobRunner`에서 공통 처리합니다.
- `marketdata`, `settlement`, `marketclose`는 batch 문서 기준에 맞춰 reader/processor/writer 또는 writer 단위로 책임을 분리합니다.
- 시세는 모든 종목을 무조건 갱신하지 않고 관심 종목, 보유 종목, 미체결 주문이 있는 종목을 우선 갱신합니다.
- 시세 provider는 `MarketPriceProvider`로 분리하고, 실제 외부 API 연동은 provider 구현 교체로 처리합니다.
- 외부 provider 장애는 해당 종목 가격 갱신만 건너뛰고 나머지 종목 처리를 계속합니다.
- 유실되면 안 되는 주문/체결 결과는 Pub/Sub이 아니라 DB 원장에 기록합니다.
- 매도 체결은 `stock_holding.quantity`와 `reserved_quantity`를 함께 차감해 미체결 매도 예약과 실제 보유 원장을 맞춥니다.
- 체결 중에는 0주가 된 `stock_holding`을 즉시 삭제하지 않습니다. 주문장 체결의 lock/write 비용을 줄이기 위해 `holding-cleanup` 유지보수 job이 시뮬레이션 시간 기준 보존 기간이 지난 빈 row만 제한 건수로 삭제합니다.
- 현재가 기준 체결과 주문장 체결은 더 이상 mode 스위치로 고르지 않고 별도 job으로 동시에 존재합니다.
- `VIRTUAL_PRICE` 주문은 현재가 기준 체결 job만 처리하고, `ORDER_BOOK` 주문은 주문장 체결 job만 처리합니다.
- 유상증자 기업 이벤트는 corporate action job이 처리합니다. 권리락일에는 이론권리락가격을 `stock_price`, `stock_price_tick`에 반영하고, 납입일에는 상태를 `PAID`로 바꾸며, 신주상장일에는 `stock_order_book_instrument.issued_shares`, `tradable_shares`를 증가시킵니다.
- 추가발행 기업 이벤트는 신주상장일에 `issued_shares`, `tradable_shares`만 증가시키고 가격은 직접 조정하지 않습니다.
- 액면분할 기업 이벤트는 효력일에 `issued_shares`, `tradable_shares`, 보유수량, 예약수량을 배율만큼 늘리고 현재가, 전일종가, 평균단가를 같은 배율로 나눕니다. 효력일에 미체결 주문이 있으면 가격/수량 기준이 꼬이지 않도록 적용을 대기합니다.
- 현금배당 기업 이벤트는 배당락일에 현재 보유수량 기준으로 `stock_corporate_action_entitlement` 지급 원장을 만듭니다. 지급일에는 해당 원장을 기준으로 `stock_account.cash_balance`를 증가시키고 중복 지급을 막기 위해 지급 원장을 `PAID`로 전이합니다. 현금배당 자체는 `stock_price`, `stock_price_tick`을 강제로 조정하지 않습니다.
- 무상증자와 주식배당 기업 이벤트는 배당락일에 이론권리락가격을 반영하고 현재 보유수량 기준으로 신주 entitlement를 만듭니다. 신주상장일에는 `issued_shares`, `tradable_shares`, 보유수량을 늘리고 평균단가를 낮춘 뒤 entitlement를 `PAID`로 전이합니다.
- 체결 수수료와 매도 거래세는 체결 단위로 계산해 `stock_execution`에 `fee_amount`, `tax_amount`, `net_amount`, `realized_profit`으로 기록합니다. 매수 평균단가는 수수료 포함 원가 기준입니다.
- 자동장 job은 자동 참여자 주문을 실제 `stock_order` 원장에 공급합니다. 내부 주문장 체결은 별도 `order-book-execution` job이 처리하므로, 브라우저 localStorage나 프론트 전용 가짜 주문 상태에 의존하지 않습니다.
- 자동장 job은 최신 `stock_instrument_report_event`의 점수를 읽어 참여자별 성향과 섞습니다. 참여자 성향은 계속 주된 기준이고, 보고서는 관리자가 부여한 종목별 시장 해석 신호입니다.
- 주문장 시장가 주문은 반대편 지정가 호가가 있을 때만 체결합니다. 양쪽 모두 시장가인 주문은 기준 가격이 없기 때문에 체결 대상에서 제외합니다.
- 내부 주문장 모드는 자전거래 방지를 위해 같은 사용자끼리의 매수/매도 주문은 매칭하지 않습니다.
- 시뮬레이션 장마감은 먼저 시장 상태를 `CLOSED`로 내려 주문/체결/자동주문을 차단하고, 장마감 후처리인 기준가 롤오버와 포트폴리오 정산이 실제 완료된 뒤에만 다음 일자/다음 장으로 이동할 수 있습니다. 프로젝트 하루는 batch 서버 heartbeat 기준 현실 2시간이며, 서버가 꺼져 heartbeat가 멈추면 시뮬레이션 시간도 마지막 heartbeat 시점에서 멈춥니다. 운영 점검이나 smoke에서는 `POST /internal/stock-batch/v1/jobs/market-close/rollover`, `POST /internal/stock-batch/v1/jobs/portfolio-settlement/run` 수동 job API를 사용합니다.
