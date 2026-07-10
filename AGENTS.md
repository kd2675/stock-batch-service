<!-- Parent: ../AGENTS.md -->
<!-- Updated: 2026-06-17 -->

# stock-batch-service

## Purpose

주식 모의투자 서비스의 배치/워커 서버입니다. 외부 시세 수집, 최신가 캐시 갱신, 미체결 주문 체결 판단, 일별 정산과 랭킹 갱신을 담당합니다.

## Key Paths

- `src/main/java/stock/batch/service/batch`
- `src/main/java/stock/batch/service/marketdata`
- `src/main/java/stock/batch/service/execution`
- `src/main/java/stock/batch/service/marketclose`
- `src/main/java/stock/batch/service/settlement`
- `src/main/java/stock/batch/service/scheduler`
- `src/main/resources/application*.yml`

## Internal API Surface

- `/internal/stock-batch/v1/system/status`
- `/internal/stock-batch/v1/jobs/market-data/refresh`
- `/internal/stock-batch/v1/jobs/order-book-execution/run`
- `/internal/stock-batch/v1/jobs/auto-participant-cash-flow/run`
- `/internal/stock-batch/v1/jobs/auto-participant-cash-flow/status`
- `/internal/stock-batch/v1/jobs/runtime-controls`
- `/internal/stock-batch/v1/jobs/runtime-controls/{jobName}`
- `/internal/stock-batch/v1/jobs/auto-market/run`
- `/internal/stock-batch/v1/jobs/auto-market-order-expiry/run`
- `/internal/stock-batch/v1/jobs/listing-auto-market/run`
- `/internal/stock-batch/v1/jobs/portfolio-settlement/run`
- `/internal/stock-batch/v1/jobs/market-close/rollover`
- `/internal/stock-batch/v1/jobs/corporate-actions/run`

## Run / Check

```bash
./gradlew :stock-batch-service:bootRun
./gradlew :stock-batch-service:bootRun --args='--spring.profiles.active=local-direct'
./gradlew :stock-batch-service:compileJava
./gradlew :stock-batch-service:test
```

## Operational Notes

- 포트: `local/local-direct/dev 20481`, `test 30481`, `prod 10481`
- 기본 로컬 개발은 `local-direct` profile이며 Eureka/Discovery를 끕니다.
- Gateway/Eureka 경유로 되돌릴 때는 기존 `local` profile을 사용합니다.
- Redis는 최신 시세 캐시와 가격 변경 이벤트 전달에 사용합니다.
- Redis 가격 캐시 key는 `stock:price:{symbol}`이며 값은 현재 단일 가격 문자열입니다.
- 시세 provider는 `MarketPriceProvider` 구현으로 교체하고, 스케줄러나 체결 서비스에 외부 API 호출을 직접 넣지 않습니다.
- KIS OpenAPI provider는 `stock.batch.market-data.provider=kis`에서만 활성화하고, 키/시크릿은 `KIS_APP_KEY`, `KIS_APP_SECRET` 환경변수로만 주입합니다.
- 수동 job API(`/internal/stock-batch/v1/jobs/**`)는 `X-Internal-Token` 헤더가 `STOCK_BATCH_INTERNAL_TOKEN`과 일치해야 실행됩니다. 기본 `local-direct`는 `local-stock-batch-internal-token`을 사용합니다. system status API는 smoke/health 용도로 열어둡니다.
- 스케줄러 자동 실행 중지/재개는 yml runtime 설정이 아니라 `STOCK_SERVICE.stock_batch_job_control.runtime_enabled` DB row로 제어합니다. row가 없으면 최초 조회 시 true로 생성합니다.
- job 중복 실행 방지는 JVM 메모리 락이 아니라 `STOCK_SERVICE.stock_batch_job_lock` DB 테이블 기준입니다.
- 서버 종료 시 `@Scheduled` 실행 중 job과 `StockBatchJobRunner` 실행 중 job은 설정된 timeout까지 완료를 기다립니다. 종료 시작 후 새 job은 실행하지 않고 `SKIPPED` 처리합니다.
- 주문/체결/잔고 원장은 `stock-back-service`와 같은 DB 계약을 기준으로 맞춥니다.
- 외부 시세 Provider 연동 전까지 가격 갱신은 mock provider로 작은 변동률을 적용합니다.
- 자동장은 `stock_auto_participant`, `stock_auto_market_config`, `stock_auto_participant_symbol_config`를 읽어 실제 `stock_order` 원장에 주문을 생성합니다.
- 장마감 기준가 롤오버는 `stock_price.current_price`를 `previous_close`로 복사해 다음 장 가격제한폭 기준가를 확정합니다.

## For AI Agents

- 사용자 API를 이 서버에 추가하지 않습니다. 외부에서 직접 호출할 API는 `stock-back-service`에 둡니다.
- Spring Batch 6.x JDBC `JobRepository`는 별도 metadata schema로 사용합니다. 현재 job 실행은 `@Scheduled`/internal API가 트리거하고, `StockBatchJobRunner`가 실행 이력을 `BATCH_*` 테이블에 기록합니다.
- `scheduler`는 실행 시점만 담당하고, 수동 API와 스케줄러는 `StockBatchJobLauncher`를 통해 `batch/<domain>/job` 컴포넌트를 실행합니다.
- job 중복 실행 방지, 스케줄러 runtime 제어, `COMPLETED`/`SKIPPED`/`FAILED` 응답 변환은 `batch/common` 경계에서 공통 처리합니다.
- 새 배치 흐름은 가능한 한 `batch/<domain>/reader`, `processor`, `writer`, `model`, `support`로 책임을 나누고, 기존 기능을 보존한 상태에서 도메인별로 점진 적용합니다.
- 체결 로직은 시세 수집 코드와 분리해 `execution` 패키지로 둡니다.
- 내부 주문장 체결은 주문장 주문만 처리합니다. 현재가 기준 자동 체결 job은 배치 서버에서 운영하지 않습니다.
- 스케줄러와 수동 internal job API는 같은 job service를 호출해 실행 모드 분기가 갈라지지 않게 유지합니다.
- 내부 주문장 체결은 가격 우선, 시간 우선으로 매칭하고 같은 사용자끼리 체결하지 않습니다.
- Redis 발행 실패는 체결/정산 원장 처리를 막지 않도록 degrade 처리합니다.
