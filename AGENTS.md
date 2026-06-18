<!-- Parent: ../AGENTS.md -->
<!-- Updated: 2026-06-17 -->

# stock-batch-service

## Purpose

주식 모의투자 서비스의 배치/워커 서버입니다. 외부 시세 수집, 최신가 캐시 갱신, 미체결 주문 체결 판단, 일별 정산과 랭킹 갱신을 담당합니다.

## Key Paths

- `src/main/java/stock/batch/service/marketdata`
- `src/main/java/stock/batch/service/execution`
- `src/main/java/stock/batch/service/settlement`
- `src/main/java/stock/batch/service/scheduler`
- `src/main/resources/application*.yml`

## Internal API Surface

- `/internal/stock-batch/v1/system/status`
- `/internal/stock-batch/v1/jobs/market-data/refresh`
- `/internal/stock-batch/v1/jobs/order-execution/run`
- `/internal/stock-batch/v1/jobs/portfolio-settlement/run`

## Run / Check

```bash
./gradlew :stock-batch-service:bootRun
./gradlew :stock-batch-service:compileJava
./gradlew :stock-batch-service:test
```

## Operational Notes

- 포트: `local/dev 20481`, `prod 10481`, `test 30481`
- Redis는 최신 시세 캐시와 가격 변경 이벤트 전달에 사용합니다.
- Redis 가격 캐시 key는 `stock:price:{symbol}`이며 값은 현재 단일 가격 문자열입니다.
- 시세 provider는 `MarketPriceProvider` 구현으로 교체하고, 스케줄러나 체결 서비스에 외부 API 호출을 직접 넣지 않습니다.
- KIS OpenAPI provider는 `stock.batch.market-data.provider=kis`에서만 활성화하고, 키/시크릿은 `KIS_APP_KEY`, `KIS_APP_SECRET` 환경변수로만 주입합니다.
- 수동 job API(`/internal/stock-batch/v1/jobs/**`)는 `STOCK_BATCH_INTERNAL_TOKEN`이 설정된 환경에서 `X-Internal-Token` 헤더로 보호합니다. system status API는 smoke/health 용도로 열어둡니다.
- 주문/체결/잔고 원장은 `stock-back-service`와 같은 DB 계약을 기준으로 맞춥니다.
- 외부 시세 Provider 연동 전까지 가격 갱신은 mock provider로 작은 변동률을 적용합니다.

## For AI Agents

- 사용자 API를 이 서버에 추가하지 않습니다. 외부에서 직접 호출할 API는 `stock-back-service`에 둡니다.
- Spring Batch 프레임워크는 재시작 가능한 잡, 청크 처리, 잡 이력 테이블이 필요해질 때 도입합니다. 초기 단계는 `@Scheduled` 기반 워커로 유지합니다.
- 체결 로직은 시세 수집 코드와 분리해 `execution` 패키지로 둡니다.
- 현재가 기준 체결과 내부 주문장 체결은 `stock.batch.execution.mode`로 선택합니다. 기본값은 `virtual-market-price`입니다.
- 스케줄러와 수동 internal job API는 같은 job service를 호출해 실행 모드 분기가 갈라지지 않게 유지합니다.
- 내부 주문장 체결은 가격 우선, 시간 우선으로 매칭하고 같은 사용자끼리 체결하지 않습니다.
- Redis 발행 실패는 체결/정산 원장 처리를 막지 않도록 degrade 처리합니다.
