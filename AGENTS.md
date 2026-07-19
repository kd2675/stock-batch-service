<!-- Parent: ../AGENTS.md -->
<!-- Updated: 2026-07-16 -->

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
./gradlew :stock-batch-service:mysqlTest
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
- MySQL business DDL은 `stock-back-service/src/main/resources/db/ddl/stock_all.sql`을 단일 canonical source로 사용하며, batch에는 full DDL 사본을 두지 않습니다. `src/main/resources/db/ddl/stock_h2.sql`은 batch test schema입니다.
- 외부 시세 Provider 연동 전까지 가격 갱신은 mock provider로 작은 변동률을 적용합니다.
- 자동장은 `stock_auto_participant`, `stock_auto_market_config`, `stock_auto_participant_symbol_config`를 읽어 실제 `stock_order` 원장에 주문을 생성합니다.
- 장마감 기준가 롤오버는 `stock_price.current_price`를 `previous_close`로 복사해 다음 장 가격제한폭 기준가를 확정합니다.

## For AI Agents

- 사용자 API를 이 서버에 추가하지 않습니다. 외부에서 직접 호출할 API는 `stock-back-service`에 둡니다.
- Spring Batch 6.x JDBC `JobRepository`는 별도 metadata schema로 사용합니다. 장마감·포트폴리오 정산·기업 이벤트·자동 참여자 현금 지급·일별 regime 생성은 실제 Spring Batch `Job`/`Step`으로 실행하고 `BATCH_*`에 재시작 상태와 처리 count를 기록합니다.
- 체결·자동 주문·자동 주문 만료·상장주관사 유동성 공급·프로필 큐 정합화처럼 초단위로 반복되는 작업은 `LightweightBatchTask`로 실행하며 `BATCH_*` row를 만들지 않습니다. 새 작업은 실행 빈도와 재시작 필요성을 먼저 판단해 두 경계 중 하나를 선택합니다.
- `scheduler`는 실행 시점만 담당하고, 수동 API와 스케줄러는 `StockBatchJobLauncher`를 통해 `batch/<domain>/job` 컴포넌트를 실행합니다.
- job 중복 실행 방지, 스케줄러 runtime 제어, `COMPLETED`/`SKIPPED`/`FAILED` 응답 변환은 `batch/common` 경계에서 공통 처리합니다. native Job은 stable identifying parameter로 같은 `JobInstance`를 재시작하며 임의 `runId`를 붙이지 않습니다.
- 비정상 종료로 native `JobExecution`이 `STARTING`/`STARTED`/`STOPPING`에 남으면 다음 실행 노드가 해당 job의 business lock을 획득한 뒤 그 job만 `FAILED`로 복구하고 같은 `JobInstance`를 재시작합니다. 다른 job이나 다른 노드가 lock을 보유한 실행을 전역 sweep으로 변경하지 않습니다.
- 새 배치 흐름은 가능한 한 `batch/<domain>/reader`, `processor`, `writer`, `model`, `support`로 책임을 나누고, 기존 기능을 보존한 상태에서 도메인별로 점진 적용합니다.
- 체결 로직은 시세 수집 코드와 분리해 `execution` 패키지로 둡니다.
- 내부 주문장 체결은 주문장 주문만 처리합니다. 현재가 기준 자동 체결 job은 배치 서버에서 운영하지 않습니다.
- 스케줄러와 수동 internal job API는 같은 job service를 호출해 실행 모드 분기가 갈라지지 않게 유지합니다.
- 내부 주문장 체결은 가격 우선, 시간 우선으로 매칭하고 같은 사용자끼리 체결하지 않습니다.
- Redis 발행 실패는 체결/정산 원장 처리를 막지 않도록 degrade 처리합니다.
- 주문·체결 hot path에는 전역 row lock, 범위 `FOR UPDATE`, 무제한 반복 조회, EOD 보고서 집계를 넣지 않습니다. 종목 session fence 뒤에 계좌 ID, 보유 row, 정확한 주문 PK 순으로 잠그고 모든 대상 수와 트랜잭션 chunk에 상한을 둡니다.
- 장마감·정산·기업행사·보고서 작업은 정규장 체결 worker와 DB 자원을 경쟁하지 않도록 cycle phase와 heavy-job admission control을 거칩니다. 한 시점에 무거운 EOD Job은 하나만 허용하고, 18시 hot path에는 원장 동결에 필요한 작업만 둡니다.
- 자동 주문 worker/chunk/계좌당 주문 수, 체결 worker 수, 만료·유동성 공급 batch size를 동시에 키우지 않습니다. 기본 최악치 계획량과 실제 저장량을 구분하고, 설정 증가는 동일 데이터·동일 동시성 MySQL A/B에서 TPS 95% 이상과 지연·lock wait·deadlock 기준을 통과한 뒤에만 허용합니다.
- `stock_order`·`stock_execution` 인덱스 추가는 기존 실행계획이 부족하다는 `EXPLAIN ANALYZE`와 INSERT/체결 A/B 근거가 있을 때만 검토합니다. EOD 편의를 위한 인덱스는 snapshot/summary/queue 테이블에 둡니다.
- EOD 구현·배포·부하검증의 기준 문서는 `docs/stock-eod-refactoring-plan-2026-07-15.md`입니다. Java/H2 테스트만 통과한 상태를 “주문·체결 부하 문제 없음”으로 판정하지 않습니다.
- `mysqlTest`는 일반 `test`/`check`에서 분리된 Testcontainers MySQL 8 검증입니다. Docker 부재로 테스트가 skipped된 `BUILD SUCCESSFUL`은 통과로 간주하지 않고 XML의 실행·skip·failure 수를 확인합니다. 실제 배포 승인은 같은 데이터·같은 동시성의 사용자 주문 API와 체결 p95/p99·TPS·commit·lock wait·deadlock A/B까지 통과해야 합니다.
- 장중 체결 계좌 일일요약은 원본 체결의 권위 원장이 아닙니다. after-commit에서는 DB를 쓰지 않고 `(거래일, 계좌)`별 메모리 누적값만 병합해 객체 수가 체결 건수에 비례하지 않게 합니다. 실패 재병합도 고유 슬롯 설정 상한을 넘지 않아야 하며, 초과분은 원본 체결을 지연·실패시키지 않고 저카디널리티 metric으로만 기록한 뒤 야간 `[T, T+1)` `stock_execution` 인덱스 범위 재구축으로 복구합니다.
- Spring Batch metadata archive/purge는 정규장이나 일반 polling 경로에 추가하지 않습니다. `REPORTS_AGGREGATED` 이후 cycle 소속 경량 작업으로만 실행하고, `STOCK_BATCH_METADATA` 전용 DDL·보존기간·purge allow-list·동일 MySQL 호스트 주문/체결 A/B가 승인될 때까지 `metadata-retention.enabled=false`, `purge-enabled=false`를 유지합니다.
