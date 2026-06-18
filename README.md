# stock-batch-service

주식 모의투자 서비스의 배치/워커 서버입니다.

## 역할

- 외부 주식 API에서 필요한 종목 시세 수집
- Redis 최신가 캐시 갱신
- 가격 변경 이벤트 발행
- 미체결 주문 체결 조건 검사
- 일별 평가금액, 수익률, 랭킹 정산

## 현재 API

- `GET /internal/stock-batch/v1/system/status`
- `POST /internal/stock-batch/v1/jobs/market-data/refresh`
- `POST /internal/stock-batch/v1/jobs/order-execution/run`
- `POST /internal/stock-batch/v1/jobs/portfolio-settlement/run`

## 현재 잡

- `MarketDataRefreshScheduler`: 관심/보유/미체결 종목 가격 갱신, Redis 최신가/채널 발행
- `MarketPriceProvider`: 실제 시세 provider 교체 지점. 현재 기본값은 `stock.batch.market-data.provider=mock`
- `KisMarketPriceProvider`: `stock.batch.market-data.provider=kis`일 때 KIS OpenAPI 국내주식 현재가 시세를 호출
- `OrderExecutionScheduler`: 미체결 주문 스캔, 현재가 기반 가상 체결
- `InternalOrderBookExecutionService`: `stock.batch.execution.mode=internal-order-book`일 때 사용자 매수/매도 주문을 가격 우선, 시간 우선으로 매칭
  - 지정가끼리는 교차된 호가를 체결합니다.
  - 시장가 매수는 최우선 지정가 매도 호가와, 시장가 매도는 최우선 지정가 매수 호가와 체결합니다.
  - 양쪽이 모두 시장가이면 가격 기준이 없으므로 체결하지 않고 다음 스캔을 기다립니다.
- `PortfolioSettlementScheduler`: 일별 자산/수익률 스냅샷 정산

## 실행과 검증

아래 명령은 `zeroq-common` 루트에서 실행합니다.

```bash
./gradlew :stock-batch-service:bootRun
./gradlew :stock-batch-service:bootRun --args='--spring.profiles.active=local'
./gradlew :stock-batch-service:compileJava
./gradlew :stock-batch-service:test
scripts/stock-smoke.sh
STOCK_SMOKE_RUN_BATCH_JOBS=true scripts/stock-smoke.sh
STOCK_BATCH_INTERNAL_TOKEN=<token> STOCK_SMOKE_RUN_BATCH_JOBS=true scripts/stock-smoke.sh
ZEROQ_GATEWAY_SHARED_SECRET=<secret> STOCK_BATCH_INTERNAL_TOKEN=<token> STOCK_SMOKE_RUN_GATEWAY_BATCH_JOBS=true scripts/stock-smoke.sh
scripts/stock-gateway-h2-smoke.sh
```

## 포트

| Profile | Port |
|---|---:|
| `local` | `20481` |
| `dev` | `20481` |
| `prod` | `10481` |
| `test` | `30481` |

## 내부 의존성

- `web-common-core`

## 데이터베이스 / Redis

- DB schema: `STOCK_SERVICE`
- DDL: `src/main/resources/db/ddl/stock_all.sql`
- 루트 `.env` 또는 `stock-batch-service/.env`는 optional import로 읽습니다.
- 독립 저장소로 실행할 때는 `.env.example`을 기준으로 로컬 환경 변수를 맞춥니다.
- `local`/`dev` 기본값은 다른 백엔드 서비스와 맞춰 원격 개발 MySQL `kimd0.iptime.org:23306`과 Redis `kimd0.iptime.org:26379`입니다.
- 별도 로컬 MySQL/Redis를 쓰려면 `.env`에서 `STOCK_DB_URL`, `STOCK_REDIS_HOST`, `STOCK_REDIS_PORT`를 직접 오버라이드합니다.
- `prod`는 DB와 Redis 값을 환경 변수로 명시 주입합니다.
- batch는 JPA entity/repository 서버가 아니라 `JdbcTemplate` 기반 워커이므로, stock-back의 `database/pub/PubDataConfig` 구조를 복제하지 않고 Spring Boot 단일 `spring.datasource` 자동 구성을 사용합니다.
- Hikari 풀은 local/dev 기본 8개이며, prod는 `STOCK_DB_MAX_POOL_SIZE`, `STOCK_DB_CONNECTION_TIMEOUT`, `STOCK_DB_MAX_LIFETIME`, `STOCK_DB_KEEPALIVE_TIME`로 조정합니다.
- DDL은 기본 관심 종목과 최초 가격을 idempotent seed로 넣어 stock-back보다 batch가 먼저 떠도 가격 수집 대상이 비지 않도록 합니다.
- Redis key: `stock:price:{symbol}`. 값은 현재 단일 가격 문자열이며 `stock-back-service` 시장 가격 API가 우선 조회합니다. TTL 기본값은 60초이며 `STOCK_PRICE_CACHE_TTL_SECONDS`로 조정합니다.
- Redis channel: `stock.price.{symbol}`
- Redis에는 최신가 문자열과 pub/sub 메시지만 다루므로 `StringRedisTemplate` 기반 설정을 사용합니다. JSON Redis serializer는 현재 Spring Data Redis 4.x에서 removal deprecated 경고가 있어 사용하지 않습니다.
- 가격 수집 이력은 `stock_price_tick`에 append-only로 저장하며, `stock-back-service`의 `/api/stock/v1/markets/prices/{symbol}/ticks` API가 최근 이력을 조회합니다.
- 정산 평가는 DB 현재가를 우선 사용하되, 내부 주문장 체결처럼 아직 `stock_price`가 없는 보유 종목은 보유 평단가로 fallback합니다.

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
- `stock.batch.market-data.kis.base-url`
- `stock.batch.market-data.kis.app-key`
- `stock.batch.market-data.kis.app-secret`
- `stock.batch.market-data.kis.market-div-code`
- `stock.batch.execution.mode`: `virtual-market-price` 또는 `internal-order-book`
- `stock.batch.settlement.cron`: 장 마감 정산 cron. 기본값 `0 40 15 * * MON-FRI`
- `stock.batch.settlement.zone`: 장 마감 정산 기준 timezone. 기본값 `Asia/Seoul`

KIS provider는 OAuth 접근토큰을 발급받은 뒤 `/uapi/domestic-stock/v1/quotations/inquire-price`를 호출하고, 응답의 현재가를 `stock_price`, `stock_price_tick`, Redis 최신가 캐시에 반영합니다.

## 내부 Job API 보호

`GET /internal/stock-batch/v1/system/status`는 상태 확인과 smoke check를 위해 열어둡니다.

`POST /internal/stock-batch/v1/jobs/**` 실행 API는 `STOCK_BATCH_INTERNAL_TOKEN`을 설정하면 `X-Internal-Token` 헤더가 일치해야 실행됩니다. 토큰이 비어 있으면 기본적으로 차단하며, `local`/`test` profile에서만 `stock.batch.internal.allow-empty-token=true`로 smoke 편의를 허용합니다.

```bash
STOCK_BATCH_INTERNAL_TOKEN=change-me ./gradlew :stock-batch-service:bootRun
curl -X POST http://localhost:20481/internal/stock-batch/v1/jobs/order-execution/run \
  -H 'X-Internal-Token: change-me'
```

Cloud Gateway를 통할 때도 batch 서버의 내부 토큰 검증은 유지합니다. Gateway의 `/internal/stock-batch/v1/jobs/**` 경로는 `ZEROQ_GATEWAY_SHARED_SECRET` 기반 HMAC 인증을 먼저 요구하고, 통과한 요청에 `X-Internal-Token`을 주입해 `stock-batch-service`로 전달합니다.
`scripts/stock-gateway-h2-smoke.sh`는 auth-back, stock-back, stock-batch, cloud gateway를 H2 profile로 함께 띄워 gateway HMAC job route와 JWT 기반 stock 보호 API를 동시에 검증합니다.

Job 응답의 `data.status`는 `COMPLETED`, `SKIPPED`, `FAILED` 중 하나입니다. 같은 job이 이미 실행 중이면 `SKIPPED`로 응답하고, provider/DB 등 내부 실행 오류가 발생하면 `FAILED`와 `message`로 실패 사유를 반환합니다.

## 패키지 경계

- `marketdata`: 외부 시세 Provider client, 최신가 캐시 writer
- `execution`: 미체결 주문 조회와 가상 체결 판단
- `settlement`: 일별 평가, 수익률, 랭킹 정산
- `scheduler`: 잡 트리거와 실행 주기 관리

## 설계 기준

- 초기 단계에서는 Spring Batch 메타 테이블을 만들지 않고 `@Scheduled` 기반 워커로 시작합니다.
- 시세는 모든 종목을 무조건 갱신하지 않고 관심 종목, 보유 종목, 미체결 주문이 있는 종목을 우선 갱신합니다.
- 시세 provider는 `MarketPriceProvider`로 분리하고, 실제 외부 API 연동은 provider 구현 교체로 처리합니다.
- 외부 provider 장애는 해당 종목 가격 갱신만 건너뛰고 나머지 종목 처리를 계속합니다.
- 유실되면 안 되는 주문/체결 결과는 Pub/Sub이 아니라 DB 원장에 기록합니다.
- 매도 체결은 `stock_holding.quantity`와 `reserved_quantity`를 함께 차감해 미체결 매도 예약과 실제 보유 원장을 맞춥니다.
- 기본 체결 모드는 `virtual-market-price`입니다. 내부 사용자 간 수요/공급 매칭은 `internal-order-book` 모드로 전환해 같은 `stock_order` 원장 위에서 실행합니다.
- `internal-order-book` 모드의 시장가 주문은 반대편 지정가 호가가 있을 때만 체결합니다. 양쪽 모두 시장가인 주문은 기준 가격이 없기 때문에 체결 대상에서 제외합니다.
- 내부 주문장 모드는 자전거래 방지를 위해 같은 사용자끼리의 매수/매도 주문은 매칭하지 않습니다.
- 장 마감 정산 스케줄은 기본적으로 평일 15:40 `Asia/Seoul` 기준으로 실행하며, 운영 점검이나 smoke에서는 `POST /internal/stock-batch/v1/jobs/portfolio-settlement/run` 수동 job API를 사용합니다.
