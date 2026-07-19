# 주식시장 EOD·장마감 파이프라인 종합 리팩터링 계획

- 작성일: 2026-07-15
- 대상: `stock-batch-service`, `stock-back-service`, `stock-front-service`, `web-common-core`
- 성격: 현재 코드·DB·로그·배치 메타데이터 분석을 바탕으로 한 구현 계획
- 주의: 이 문서는 계획과 구현 상태를 함께 추적한다. 핵심 EOD 소스와 정적 계약은 구현했지만 shadow 비교, 운영 DB ALTER, 실제 MySQL 동시성·거래량 A/B, DB 전체 부하 신호 기반 admission과 재기동 live 검증은 아직 완료하지 않았다. 계획 문서와 핵심 신규 fence·cycle·coordinator·EOD API/UI 파일도 현재 각 하위 저장소에서 미추적 상태이므로 로컬 소스 구현과 Git 배포 완료를 같은 의미로 취급하지 않는다. 서버 프로세스는 에이전트가 종료하지 않는다.

## 종합 결론

제시한 방향이 맞습니다.

> T일 18시에 거래 원장만 빠르고 정확하게 동결하고, 포트폴리오 정산은 잠시 뒤 실행하며, 기업행사·현금흐름·차트 집계·정리 작업은 T+1일 00시 이후, 가격·자동시장·Redis 준비는 개장 직전으로 분산해야 합니다.

다만 이는 스케줄 시각만 옮기는 작업이 아닙니다. 먼저 다음 네 가지를 동일한 모델로 묶어야 합니다.

1. 종목별 거래 세션 fence
2. 원시 시뮬레이션 시각과 활성 거래일 분리
3. 거래일별 close cycle과 단계 상태
4. close cycle에 귀속된 불변 스냅샷

이 네 가지 없이 단순히 포트폴리오 정산이나 기업행사를 늦추면, 늦어진 시간 동안 현금·가격·보유량이 변경되어 현재보다 정산 오차가 더 커집니다.

### 거래량 보호 최상위 원칙

이 계획의 정확성 요구보다 주문·체결 지연을 낮은 우선순위로 취급해서는 안 됩니다. 모든 단계는 다음 조건을 동시에 만족해야 합니다.

> 2026-07-16 추가 실행 제약: 이후 남은 구현·DDL·검증도 현재 주문량과 체결량을 기준으로 수행한다. 정규장에 실행될 가능성이 있거나 주문·체결의 SQL 수·잠금 행·커밋·인덱스 쓰기를 늘리는 변경은 먼저 동일 데이터 A/B 근거를 확보하지 못하면 적용하지 않는다. 실측할 수 없는 변경은 야간 phase·shadow·기본 비활성 상태로만 두며, 운영 DB ALTER는 백엔드와 배치가 모두 종료됐다고 사용자가 확인한 유지보수 창에서만 수행한다.

1. 정규장 주문·정정·자동 주문·체결 경로에는 종목 PK fence 조회 1회 외의 EOD 쿼리를 넣지 않는다.
2. 거래 중 전역 시계·거래일·cycle 행을 잠그지 않는다. cycle/attempt는 제어면에서만 접근한다.
3. 장마감은 종목 fence를 닫은 뒤 실행하며, 대형 `stock_order`·`stock_execution` 조회가 정규장 거래와 겹치지 않게 한다.
4. 미체결 주문은 기존 `(market_type, status, symbol)` 인덱스에서 열린 symbol만 찾고, 고정 prefix 뒤 InnoDB가 확장한 clustered PK `id` keyset `INSERT ... SELECT`로 정확한 cohort를 1,000건씩 먼저 고정한 뒤 취소·반환은 500건씩 처리한다. 매 거래일 과거 종료 주문을 PK 처음부터 다시 읽거나, 이를 피하려고 정규장 주문 INSERT용 인덱스를 새로 추가하지 않는다. JVM 전체 적재, 주문별 DB 왕복, 대형 단일 트랜잭션을 모두 금지한다.
5. 야간 집계·기업행사·보고서는 한 번에 하나의 무거운 작업만 허용하고, 다음 개장 준비 완료 시각을 넘지 않는 범위에서 분산한다.
6. 각 단계는 동일 데이터·동일 동시성 A/B 부하검증을 통과해야 하며, 주문 TPS·체결 지연 회귀가 허용치를 넘으면 다음 단계로 전환하지 않는다.
7. 단순 connection pool·worker 증설로 처리량을 맞추지 않는다. 쿼리 수, 잠금 범위, 작업 중첩, JobRepository 쓰기를 먼저 줄인다.
8. 관리자 화면의 주기 조회는 `stock_order`, `stock_execution` 또는 스냅샷 원장을 다시 집계하지 않는다. EOD 단계가 한 번 기록한 소형 요약 row만 PK/index 조회한다.
9. 관리자 EOD 조회는 해당 화면이 열려 있을 때만 15초 주기로 실행하고 백그라운드 폴링을 금지한다. 10초 미만으로 줄이려면 실제 거래량 부하검증을 먼저 통과해야 한다.
10. 계좌·권리·주문 수에 비례하는 기업행사는 keyset/cohort 기반 bounded chunk로 처리하고, 한 트랜잭션이 대량 계좌 전체를 보유하지 않게 한다.
11. 모든 구현 단위는 변경 전·후의 주문/체결 DB 왕복 수, 잠금 행·잠금 순서, `stock_order`/`stock_execution` 실행계획, p95/p99를 함께 기록한다. 이 증거가 없으면 기능 테스트가 통과해도 운영 전환하지 않는다.
12. 실데이터 규모 부하검증을 아직 실행할 수 없는 단계는 기본 비활성·shadow·야간 phase에 둔다. 추정 처리량만으로 정규장 hot path나 hot-ledger 인덱스를 바꾸지 않는다.
13. 다중 종목 주문 청크의 보유 잠금은 계좌 집합과 종목 집합의 교차조합이 아니라 frozen 주문에 실제 존재하는 `(account_id, symbol)` 복합키만 사용한다. 후보 500건이면 보유 잠금 키도 최대 500개이며 종목 수 증가로 무관한 잠금이 곱셈 증폭되지 않아야 한다.
14. 계좌 스냅샷의 watermark·청약·대사 조회는 계좌 청크마다 같은 저빈도 원장을 반복 스캔하지 않게 `(account_id, id)`, `(account_id, status)`, `(close_cycle_id, reconciliation_status, account_id)` 인덱스를 사용한다. 이 인덱스는 현금흐름·권리·EOD 스냅샷에만 두고 정규장 `stock_order`·`stock_execution` INSERT 인덱스 수는 늘리지 않는다.
15. PRE_OPEN 외부 시세는 정규장 폴링과 EOD 완료 정책을 분리한다. 정규장 경량 폴링은 종목별 부분 성공을 허용하지만, close cycle의 `MARKET_DATA_PREPARED` 전이는 모든 대상의 DB 갱신과 Redis publish가 성공해야 한다. 재시도는 같은 `(symbol, price_time, price, provider)` tick을 다시 쓰지 않으며, 이 검증과 멱등화는 `VIRTUAL_PRICE` 활성 주문·보유 및 가격 원장에만 적용하고 주문장 주문·체결 쓰기 경로에는 추가 SQL을 넣지 않는다.
16. scheduler thread가 다르더라도 native Spring Batch Job 두 개 또는 cycle 소속 lightweight 유지보수 두 개를 동시에 실행하지 않는다. 공통 `post-close-heavy-admission` lease를 먼저 획득한 작업 하나만 실행하고 job lock·cycle lease와 함께 heartbeat한다. 체결 worker·자동 주문·주문 만료·상장주관사 공급처럼 정규장 경량 실행기는 이 admission을 사용하지 않는다.
17. 유상증자 자동청약은 계좌 수만큼 `SELECT FOR UPDATE`·현금 UPDATE·권리 UPDATE/INSERT·현금흐름 INSERT를 반복하지 않는다. action 행을 먼저 잠근 뒤 계좌 PK와 권리 PK를 각각 오름차순으로 한 번씩 잠그고, 200계좌 청크의 성공 결정만 CASE UPDATE·다중 INSERT로 반영한다. 수동청약과 같은 `action → account → entitlement` 잠금 순서를 유지하며 주문·체결 원장은 읽거나 쓰지 않는다.
18. 자동 주문 만료·수동 종목 취소·상장폐지처럼 주문을 정리하는 경로도 정확한 주문 PK를 잠근 뒤 청크당 한 번의 주문 UPDATE만 허용한다. 예약 현금은 계좌 CASE UPDATE, 예약 매도수량은 실제 `(account_id, symbol)` 집합 CASE UPDATE로 반환하며 주문/계좌/보유 건수 불일치 시 청크 전체를 롤백한다.
19. 정규장 자동 주문 생성도 계좌·보유마다 예약 UPDATE를 반복하지 않는다. 기본 25명, 허용 최대 100명의 참여자 청크에서 계좌 상태와 실제 매도 보유 복합키를 각각 한 번 잠가 읽고, 수락 가능한 매수 현금은 계좌 CASE UPDATE 1회, 매도수량은 보유 CASE UPDATE 1회, 주문은 명시적 multi-row INSERT 1회로 반영한다. 잔액·보유 부족 조건을 SQL에서 다시 검증하고 갱신 건수 불일치 시 청크 전체를 롤백한다. Connector/J의 `rewriteBatchedStatements` 설정 유무에 처리량을 의존하지 않으며 주문 INSERT 한 문장은 최대 800행으로 제한한다.
20. 자동 참여자 due 후보는 활성 종목·이번 run의 claim profile·참여자 수를 모두 곱한 중간 행 예산을 가진다. `generation-candidate-row-limit` 기본 2,000·최대 10,000을 두고 profile claim 수를 `min(worker-limit, floor(row-budget / symbol-count))`, profile별 참여자 limit을 `min(due-limit, floor(row-budget / (symbol-count × claimed-profile-count)))`로 계산한다. 활성 종목 수가 예산보다 많으면 조합 쿼리를 실행하지 않는다. 종목 수와 profile 수가 늘 때 후보가 곱셈 증폭되는 구조를 허용하지 않는다.
21. 자동 참여자 schedule lease는 참여자별 UPDATE를 금지하고, 선택된 user key 집합을 한 번의 조건부 UPDATE로 claim한다. 다중 인스턴스 경합으로 일부만 claim된 경우에만 owner·lease 조건으로 한 번 재조회한다.
22. Redis ready-symbol 큐가 체결의 정상 경로이며 DB fallback은 복구 경로다. fallback은 기본 30초·최소 10초로 제한하고, 5초 이하 polling으로 체결 반응시간을 맞추지 않는다. 신규·정정 주문 after-commit enqueue, worker 처리시간, queue 누락률을 먼저 개선한다.
23. 정규장 scheduler와 executor 설정은 운영 환경변수여도 무제한 확장할 수 없다. 체결 worker 1~8, execution scheduler 1~4, 자동 생성 executor 1~16·queue 0, auto-market run dispatcher 1/1/0, auto-market·maintenance·simulation-clock scheduler 1 thread를 기동 시 강제한다.
24. 정규장 polling 주기는 자동 주문 최소 5초, 자동 주문 만료·상장주관사 공급 최소 5초, profile queue reconcile 최소 60초, 체결 DB fallback 최소 10초로 강제한다. 환경변수 오타나 과도한 튜닝이 millisecond 단위 scheduler wake-up·DB gate 조회·rejection 로그 폭주로 이어지지 않게 한다.
25. 체결 파생요약 pending 저장소는 체결 건별 객체 큐가 아니라 `(거래일, 계좌)` 키별 누적값으로 즉시 병합한다. 기본 10만·최대 100만은 체결 건수가 아니라 고유 account-day 슬롯 상한이며, 같은 계좌의 체결이 수만 건 발생해도 슬롯과 flush row는 1개다. 슬롯 초과는 원본 체결을 실패시키지 않고 파생 실시간 요약만 포기하며 야간 원본 범위 재구축으로 복구한다. 메모리 상한을 환경값으로 무제한 확대하지 않는다.
26. 자동 주문 모멘텀 기준가격의 per-symbol 최신값 조회는 윈도우 정렬 대신 index point-range `UNION ALL`을 유지하되 한 SQL에 최대 100종목만 포함한다. due 참여자가 0이면 실행하지 않고, 종목 수 증가가 SQL 문자열·placeholder·optimizer 파싱 비용의 무제한 증가로 이어지지 않게 한다.
27. 자동 주문 만료와 상장주관사 공급은 종목당 후보 상한뿐 아니라 한 run의 종목 수도 기본 100·최대 500으로 제한한다. 활성 종목이 상한을 넘으면 현실 10초 버킷의 결정적 symbol slice를 순환하여 다중 서버의 로컬 cursor 불일치 없이 공정하게 처리하고, 한 run이 단일 auto-market scheduler를 장시간 점유하지 않게 한다.
28. 정규장 metric은 symbol·account·order ID 같은 고카디널리티 tag를 금지한다. 만료·상장주관사 공급의 symbol-lock skip은 전역 counter로 집계하고 개별 종목은 debug 로그나 bounded 진단 API에서만 확인한다.
29. 자동 주문 만료·상장주관사 공급의 활성 설정 조회는 자동 주문 생성 전용 최신 보고서 점수·분포 편향을 읽지 않는다. TTL·현재가·틱·수량만 반환하는 경량 쿼리로 분리하여 10초 polling이 보고서 이벤트 상관 서브쿼리를 반복하지 않게 한다.
30. 자동 주문의 주 랜덤 regime·30분 보조 modifier는 5초 run마다 종목별 INSERT와 `DuplicateKeyException`을 발생시키지 않는다. 현재 키를 최대 500종목씩 조회하고 누락 row만 최대 500행 multi-row로 저장하며, 같은 거래일·구간 steady state의 쓰기는 0회여야 한다. 종목 수가 500을 넘더라도 SQL 문자열·placeholder·예외 생성 비용이 무제한 증가하지 않아야 한다.
31. 주문 생성 후 schedule 완료도 참여자별 UPDATE를 금지한다. 고유 참여자를 최대 100명씩 묶어 profile·개별 next-run·interval·priority·lease 해제를 한 CASE UPDATE로 수행하며, 기본 25명 주문 청크의 추가 원격 쓰기는 정확히 1회로 제한한다.
32. 5초 자동 주문 run은 Redis ready-profile zset에 현재 due profile이 없으면 전체 종목 설정·최신 보고서·프로필 정책·regime DB 조회를 시작하지 않는다. Redis due 확인은 읽기 전용 1회이며 실패 시 fail-closed한다. due가 있더라도 실제 profile claim이 모두 경합으로 실패하면 정책·regime 조회를 생략한다.
33. 장중 체결 계좌 요약은 파생 read model일 뿐 원본 체결의 권위 저장소가 아니다. after-commit에서는 DB를 쓰지 않고 `(거래일, 계좌)` 누적값만 병합한다. 정상 누적뿐 아니라 DB flush 실패분 재병합도 `max-pending-deltas` 고유 슬롯 상한을 절대 넘지 않으며, 새 계좌 슬롯이 상한을 먼저 채운 경우 오래된 실패분은 `requeue-overflow` 지표로 폐기하고 야간 원본 재구축으로 복구한다. 파생 요약 보존을 위해 체결 after-commit 경로를 block하거나 원본 체결을 실패시키지 않는다.
34. EOD phase가 `FAILED`·`DEFERRED`가 되어도 10초 coordinator poll마다 같은 Job을 다시 실행하지 않는다. 실패는 cycle 전체 누적 attempt가 아니라 **현재 phase의 실패 횟수**를 기준으로 서버 시각 30초부터 최대 900초까지 지수 백오프하고, 정책상 연기는 60초 뒤 재판정하며 `stock_post_close_cycle.next_retry_at`에 영속화한다. 늦은 readiness phase의 첫 실패가 앞 단계 성공 횟수 때문에 곧바로 15분 대기가 되지 않아야 한다. 백오프 전 poll은 cycle 제어행만 읽고 claim UPDATE·JobRepository·업무 원장 조회를 모두 생략한다. 이 필드는 주문·체결 transaction에서 읽거나 잠그지 않는다.
35. coordinator 모드의 정규장 profile ready-queue 복구는 서버 시작과 기본 10분 주기만 허용한다. 실제 주문장이 OPEN인 경우 작은 profile·schedule·config 테이블로 Redis 집합을 복구하되 Spring Batch Job과 `BATCH_*` 메타데이터를 만들지 않고 `stock_order`·`stock_execution`은 조회하지 않는다. Redis 유실이나 claim 직후 JVM 종료를 복구하기 위해 PRE_OPEN만 기다리지는 않되, 이 경로를 주문 생성 5초 run에 합치거나 polling 주기를 줄이지 않는다.
36. FAILED Job의 양수 write count를 일반적인 “계속 진행 중” 신호로 사용하지 않는다. `BOUNDED_PROGRESS` 빠른 재개는 최종 validation이 의도적으로 잔여 cohort를 알리는 기업행사 CASH/PREOPEN phase에만 허용한다. 보고서·정산·시세 Job이 일부 row를 쓴 뒤 실패하면 양수 count가 있더라도 일반 지수 백오프를 적용해 같은 대형 원장 범위를 30초마다 재조회하지 않는다.
37. 장중 화면의 집계 조회도 거래량 비회귀 대상이다. 관리자 전체 체결 건수는 `stock_execution` 거래일 범위 `COUNT` 대신 체결 after-commit 누적값이 30초 단위로 적재하는 `stock_execution_account_day_summary`의 해당 날짜 소수 행만 합산하며, BUY·SELL 두 계좌 delta를 2로 나눠 거래 건수를 계산한다. 자동 참여자 당일 체결 수도 같은 요약을 활성 자동 참여자 계좌와 조인하며 원본 체결 범위를 세지 않는다. 사용자 누적 손익과 관리자 자금흐름의 체결금액·수수료·세금·실현손익도 계좌별 전체 `stock_execution`을 다시 합산하지 않고 일별 요약을 합산한다. 현재 장중 값은 정상 flush 상태에서 약 30초 지연될 수 있음을 화면에 표시하고, flush 실패·프로세스 재기동·요약 슬롯 상한 초과 시에는 더 늦을 수 있음도 명시하며, 야간 REPORTS 단계가 `[거래일 시작, 다음 거래일 시작)` 범위만 원본과 대사해 정확 값을 만든다. 종목 거래요약·캔들처럼 아직 원본 체결 범위를 읽어야 하는 API는 서버 인스턴스별 10초 TTL·single-flight·최대 1,000키 읽기 캐시와 5초 statement timeout으로 중복 조회와 꼬리 지연을 격리한다. 프론트 집계 폴링도 최소 10초이고 background refetch는 공통 기본값으로 금지한다. 이 보호는 읽기 계층에만 존재하며 주문·체결 transaction에 cache write, summary UPSERT, 신규 인덱스 또는 잠금을 추가하지 않는다. 향후 종목별 비동기 read model을 도입하려면 현재/확장 거래량 MySQL A/B에서 추가 commit과 redo가 주문·체결 TPS 95% 게이트를 통과해야 한다.
38. 자동 월급·정기 입금은 청크별 `REQUIRES_NEW` 커밋 이후 Step이 실패해도 같은 `requestId`의 재시작이 앞 청크를 다시 지급하지 않아야 한다. 실행당 `stock_auto_participant_cash_flow_run` 제어행 1개에 마지막 완료 계좌 PK와 누적 처리 건수만 기록하고, `현금 UPDATE + 현금흐름 INSERT + cursor 전진`을 같은 야간 업무 트랜잭션으로 커밋한다. 계좌별 멱등 row를 쌓거나 `stock_order`·`stock_execution`을 조회·변경하지 않으며, 기본 200·최대 1,000계좌 청크 상한은 그대로 유지한다.
39. PRE_OPEN 프로필 큐 준비는 기존 queue를 지운 뒤 profile별 호출을 반복하는 부분 성공 구조를 사용하지 않는다. 전용 Redis zset 한 key를 Lua 1회로 `DEL + ZADD + ZCARD`하여 권위 있는 DB schedule의 distinct profile 수와 정확히 일치할 때만 `AUTO_MARKET_PREPARED`로 전진하고, Redis 예외·null 응답·건수 불일치는 phase 실패로 남긴다. 이 엄격 모드는 시장이 닫힌 PRE_OPEN에만 적용하며, 정규장 10분 복구는 기존 best-effort와 소형 profile/schedule/config 조회만 유지한다. 따라서 `stock_order`·`stock_execution` SQL·인덱스·잠금·commit은 추가하지 않는다.
40. 개장 readiness는 `AUTO_MARKET_PREPARED` 이후 PRE_OPEN에서 cycle당 한 번만 실행하고 결과도 정확히 10개의 소형 제어행으로 제한한다. 시장·fence·가격 snapshot·regime·정산 metric은 설정/스냅샷 제어 테이블의 bounded 조회, 기업행사는 processing ledger의 미완료 건수, Redis는 고정 profile enum 범위의 zset snapshot, build/schema는 cycle PK 한 행만 비교한다. `stock_order`·`stock_execution`·`stock_holding`을 읽거나 쓰지 않으며, 관리자 15초 polling은 이 결과를 `(close_cycle_id, display_order)`로만 읽는다. 이 금지는 소스 계약 테스트로 유지한다.

#### 모든 작업에 적용하는 거래량 비회귀 승인 게이트

이 문서 이후의 모든 코드·DDL·관리자 UI 작업은 기능 영역과 관계없이 먼저 `REGULAR_HOT_PATH`, `CLOSE_CRITICAL`, `OVERNIGHT_HEAVY`, `CONTROL_PLANE`, `UI_READ_MODEL` 중 하나로 분류한다. 분류 없이 구현을 시작하거나, 기능 테스트 성공만으로 완료 처리하지 않는다.

- `REGULAR_HOT_PATH` 변경은 추가 SQL 수, commit 수, 잠금 행 수, 잠금 순서, 사용 인덱스를 변경 전·후로 기록한다. 거래량이 늘 때 주문/체결 건수와 함께 선형 증가하는 EOD 조회·write·metadata가 하나라도 생기면 배포를 차단한다.
- 자동 주문은 기본 25명·허용 최대 100명의 bounded 청크에서 고유 계좌 PK를 한 번에 오름차순 잠근 뒤 `계좌 → 보유 → 주문 INSERT` 순서를 지킨다. 매도 전용 청크도 보유를 먼저 잠그지 않으며, 계좌/보유 상태 잠금과 현금/수량 예약은 각각 청크당 1회로 제한한다. 종목별 due 후보도 1~500만 허용하고 범위를 벗어난 환경값은 서버 기동 시 거부한다.
- 체결 후보 탐색은 잠금 없이 수행하되, 선택된 정확한 주문 두 행은 `PRIMARY`로 ID 오름차순 `FOR UPDATE`한다. 이 정확성 잠금에는 `SKIP LOCKED`를 사용하지 않는다. `SKIP LOCKED`는 signal·work queue claim처럼 누락을 다음 소비자가 처리할 수 있는 큐에만 허용한다.
- 장마감은 `CLOSE_REQUESTED` 논리 cycle을 먼저 커밋한 뒤 종목 fence를 닫는다. 먼저 시작한 저빈도 계좌 변경은 business-state 공유 잠금이 끝날 때까지 drain하고, cycle 생성 뒤 들어온 변경은 계좌 row를 잠그기 전에 거부한다. fence를 먼저 닫고 cycle을 나중에 만드는 무보호 구간을 허용하지 않는다.
- 테스트 프로필은 post-close coordinator, execution-account-summary, 일일 regime, 프로필 큐 reconcile, 자동 주문 만료, 상장주관사 공급 background scheduler를 명시적으로 비활성화한다. `auto-market.enabled=false`만으로 별도 scheduler 설정까지 꺼진다고 가정하지 않는다. 테스트 중 우연히 실행된 스케줄러의 DB 쓰기·잠금·메모리 큐 변경·로그를 기능 성공이나 성능 증거로 오인하지 않는다.
- 실제 MySQL A/B를 실행할 수 없는 변경은 `검증 대기`로 남긴다. Java/H2·정적 계약이 통과해도 주문 TPS 95% 이상, 주문 p95 허용 증가 이내, 체결 반응 p95/p99, lock wait/deadlock/timeout 기준을 실측하기 전에는 “부하 문제 없음”으로 판정하지 않는다.

이 게이트는 이번 EOD 리팩터링에만 한정하지 않는다. 이후 기업행사·보고서·자동시장·관리자 조회·신호 처리 변경도 같은 기준을 적용하며, 거래량 증가를 이유로 worker·DB pool부터 늘리는 변경은 승인하지 않는다.

이 규칙이 계획 문서에만 머물지 않도록 `stock-back-service/AGENTS.md`에는 hot-ledger 조회·인덱스 A/B 기준, `stock-batch-service/AGENTS.md`에는 잠금·청크·heavy-job admission 기준, `stock-front-service/AGENTS.md`에는 page-scoped polling과 요약 API 사용 기준을 각각 고정한다. 이후 관련 작업은 이 문서와 각 서비스 `AGENTS.md`를 함께 충족해야 한다.

계좌 기반 야간 작업의 설정 안전장치도 코드로 강제한다. 자동 월급·정기 입금과 기업행사는 기본 200, 허용 최대 1,000계좌이며 이를 벗어나면 서버 기동을 거부한다. 기업행사 due event 선택은 기본 25, 허용 최대 200건으로 제한해 장기 장애 뒤 누적된 action을 한 번에 JVM에 적재하지 않는다. 자동 입금의 중복 지급 확인은 window 내 현금흐름 원장을 전부 반환하지 않고 `(account_id, reason)`별 `MAX(created_at)`만 반환한다. 이 쿼리는 기존 `(account_id, reason, created_by, created_at)` 인덱스 범위를 사용하며 정규장에는 실행되지 않는다. 같은 실행 재시작의 중복 지급은 별도 실행 cursor가 차단하며, 실행별 1행만 저장하므로 계좌 수에 비례해 멱등 원장이 장기 증가하지 않는다.

모든 후속 구현은 아래 표를 변경 단위별로 채운 뒤에만 완료로 판정한다. 기능 테스트만 통과하고 이 표의 부하 근거가 없으면 운영 전환 대상이 아니다.

| 확인 항목 | 필수 증거 | 배포 차단 조건 |
|---|---|---|
| 정규장 hot path | 주문·체결 1건당 추가 SQL 수와 실행계획 | 종목 fence point lookup 외 추가 EOD SQL 발생 |
| 잠금 | 획득 순서, PK/범위 여부, p95/p99 대기 | 전역 row lock, 상태 인덱스 gap lock, 기존 lock-order 역전 |
| 쓰기 증폭 | 주문·체결 INSERT당 인덱스·원장·커밋 증가량 | EOD만을 위한 `stock_order`/`stock_execution` 쓰기 증가 |
| 야간 처리량 | 대상 행 수별 chunk 수·트랜잭션 시간·연결 점유 | 무제한 JVM 적재 또는 계좌/주문 전체 단일 트랜잭션 |
| 작업 중첩 | 동일 시간대 무거운 Job 수와 DB pool 사용량 | 정규장 실행 또는 무거운 EOD Job 2개 이상 병렬 실행 |
| 회귀 비교 | 동일 데이터·동일 동시성 A/B의 TPS, p95/p99, lock wait | 주문/체결 TPS 5% 이상 감소 또는 허용 지연 초과 |

2026-07-16에 추가한 실패 phase 재시도 명령은 `CONTROL_PLANE`으로 분류한다. 요청 한 번당 `stock_post_close_cycle` 정확한 PK 1행을 잠그고, 작은 `stock_post_close_phase_attempt`·시장 설정·cycle 인덱스만 검증한 뒤 cycle 1행의 backoff를 해제한다. `stock_order`·`stock_execution`·`stock_account`·`stock_holding`은 읽거나 쓰지 않고, 주문·체결 트랜잭션의 SQL·commit·잠금·인덱스 증가는 모두 0이다. 명령은 Job을 직접 실행하지 않으며 다음 coordinator poll이 기존 시간대·CLOSED·phase guard를 다시 통과해야 한다.

#### 현재 정규장 처리량 예산과 설정 변경 규칙

아래 값은 단순 기본값 목록이 아니라 현재 주문·체결 부하검증의 기준선이다. 운영 환경변수로 숫자를 키울 수 있다는 사실을 운영 승인으로 해석하지 않는다.

| 경로 | 현재 기본 처리량 상한 | 한 트랜잭션의 잠금·쓰기 상한 | 증설 규칙 |
|---|---:|---|---|
| 사용자 주문·취소·정정 | 요청 1건 | 종목 fence 1행, 계좌 1행, 실제 보유 최대 1행, 정확한 주문 최대 1행 | 애플리케이션 동시 요청 수를 DB pool 증설로 흡수하지 않고 API p95·lock wait 기준으로 제한 |
| 자동 참여자 주문 | profile-symbol 청크당 25계좌 | 참여자당 최대 8개이므로 기본 최악치 200 계획 주문, 계좌 최대 25행, 보유 최대 25행, 주문 INSERT 최대 200행 | `generation-participant-chunk-size`의 기술적 허용범위는 1~100이지만 25 초과는 MySQL A/B 전에는 운영 승인하지 않음 |
| 자동 참여자 병렬 생성 | profile worker 9개, executor slot 12개 | 기본 이론상 동시에 최대 225계좌·1,800 계획 주문. 실제값은 due profile·계좌 잔액·보유·탈락 정책으로 더 작지만 용량 계산은 최악치를 사용 | worker 수와 participant chunk를 동시에 올리지 않으며 `worker × chunk × 8`을 변경 전후 부하표에 기록 |
| 자동 참여자 후보 조회 | 한 run 중간 후보 행 기본 최대 2,000행 | profile·참여자 limit을 함께 조정; 9 profile 기준 3종목은 약 1,998행, 100종목은 약 1,800행 | row 예산 최대 10,000을 넘길 수 없고 활성 종목이 예산보다 많으면 무거운 조합 쿼리 실행 금지 |
| 자동 참여자 schedule 완료 | 기본 25명, SQL당 최대 100명 | 주문 생성 transaction 안에서 schedule CASE UPDATE 1문장 | 참여자별 UPDATE로 회귀 금지; 100명 초과 내부 입력은 100행씩 분할 |
| 자동시장 regime·modifier | 조회·저장 SQL당 최대 500종목/행 | 기존 row는 읽기만 하고 누락 row만 multi-row INSERT | 같은 거래일·구간 steady state 쓰기 0회, 중복 키 예외 정상 흐름 금지 |
| 내부 주문장 체결 | worker 2개 | 체결 후보 1쌍, 계좌 2행, 매도 보유 1행, 정확한 주문 2행, execution 2행 | worker 증설보다 후보 SQL·commit·symbol 공정성을 먼저 측정; 종목 수보다 worker가 많아지지 않게 함 |
| 자동 주문 만료 | 10초마다 최대 100종목, 종목당 최대 100주문 | 실제 계좌 집합, 실제 매도 보유 복합키, 정확한 주문 PK만 잠금 | 주문 상한 1,000·종목 상한 500은 비상 조정 범위이며 기본값 초과는 execution symbol-lock skip과 취소 transaction p99를 먼저 검증 |
| 상장주관사 공급 | 10초마다 최대 100종목, 종목별 설정 1건 | 만료·열린 주문 방향별 최대 200건, 신규 주문 방향별 최대 1건 | 종목 상한 500; transaction p95 100ms·p99 300ms와 체결 symbol-lock skip 1%를 넘으면 증설 금지 |
| EOD·야간 무거운 작업 | 전역 1개 | 각 단계의 명시적 keyset chunk만 처리 | 정규장에는 실행하지 않고 `post-close-heavy-admission`을 우회하지 않음 |

자동 참여자 기본 이론상 상한인 1,800건은 “한 번에 항상 저장되는 주문 수”가 아니라 9개 profile worker가 각각 25계좌에서 최대 8개를 모두 계획했을 때의 보수적 동시 상한이다. 이 수치를 평균치로 낮춰 잡아 capacity를 계산하지 않는다. 반대로 기술적 최대값인 worker 12·청크 100을 함께 적용하면 최대 9,600 계획 주문이 동시에 만들어질 수 있으므로, 두 설정을 함께 올리는 배포는 금지한다. 필요하면 계좌 청크를 키우는 대신 ready profile claim 예산, profile worker 수, symbol별 transaction 시간 예산 중 하나를 낮춰 전체 동시 잠금 행 수를 유지한다.

각 정규장 변경 PR·배포 기록에는 최소한 다음 계산을 남긴다.

```text
최대 동시 자동주문 계획 수 = min(profile-worker-count, executor-slot-count)
                           × participant-chunk-size
                           × participant당 최대 주문 수(현재 8)

최대 체결 잠금 주문 수 = execution-worker-count × 2
최대 만료 잠금 주문 수 = expiry-chunk-limit × 동시에 실행 가능한 symbol transaction 수
```

이 값이 증가했다면 기능 테스트 통과만으로 완료하지 않는다. 최소 주문 151만·체결 103만 수준의 동일 데이터에서 DB connection 사용률, commit p95/p99, row-lock wait, deadlock, 주문 API p95, 교차 주문 체결 반응 p95/p99를 함께 비교하고, 기준을 넘으면 설정과 코드를 원복한 뒤 쿼리·청크 구조부터 다시 조정한다.

### 2026-07-16 구현 완결성 재감사

최종 판정은 **핵심 소스 구현 완료, 운영 전환 미완료**입니다. 아래 항목을 구분하지 않고 “계획 전체 완료”로 보고해서는 안 됩니다.

| 구분 | 판정 | 근거·남은 일 |
|---|---|---|
| session fence·active business date | 소스 구현 | 사용자 주문·정정, 자동 주문, 상장주관사 주문, 체결의 최종 트랜잭션 검증과 장마감 fence 전환이 존재한다. |
| cycle·attempt·lease·복구 | 소스 구현 | 논리 cycle 유일성, phase attempt, owner/lease/heartbeat, 현재 phase 기준 backoff와 오래된 cycle 복구가 존재한다. |
| 불변 스냅샷·정산 | 소스 구현 | 계좌·현금·보유·가격·취소 전 주문 cohort와 frozen reader, input hash·data quality 검증이 존재한다. |
| 정산 저장 결과 대사 | 소스 구현 | 기존 frozen cohort 완료 조인 안에서 현금·평가액·총자산·수익률·보유수량·예약매도·포지션 수를 같은 산식으로 다시 계산해 저장 결과와 대조한다. 추가 운영 원장 스캔이나 정규장 이중 쓰기는 없다. |
| 시간대 EOD 협업 구조 | 소스 구현 | `PortfolioSettlementScheduler`가 18시 freeze·지연 정산 prefix를, `PostCloseCoordinatorScheduler`가 00시 이후 현금·기업행사·보고서와 04:30/05:30 준비·readiness suffix를 같은 단일 post-close executor에서 순차 실행한다. |
| 기업행사·현금흐름·signal | 소스 구현 | phase별 Step, processing ledger, bounded chunk, signal lease/backoff/DEAD_LETTER가 존재한다. 모든 신호는 요청 거래일을, 종목 신호는 session epoch까지 실행에 전달한다. `expected_cycle_id`는 요청 시점에 cycle이 이미 있으면 고정하고, 아직 없으면 날짜·범위 유일 제약으로 첫 실행에서 생성·재사용되는 동일 논리 cycle에 결합한다. |
| DDL·H2·reset | 정적 구현 | canonical `stock_all.sql`, 운영 EOD ALTER 11개, H2 DDL, 두 초기화 스크립트와 DDL 계약이 존재한다. Spring Batch metadata 전용 archive 테이블·후보 인덱스는 business ALTER와 분리된 `batch-metadata-retention-mysql.sql`에 둔다. 실제 운영 DB 적용은 별도다. |
| Git 전달 상태 | **미완료** | 구현은 현재 워킹트리에 존재하지만 2026-07-16 `git status --porcelain -uall` 파일 단위 재감사 시점에 untracked 파일이 batch 96개, back 33개, front 2개이며 이 문서 자체도 untracked다. 이 중 `src/main` 또는 프론트 `app` production 경로는 batch 49개, back 21개, front 2개다. staging·commit을 요청받지 않았으므로 Git 이력이나 배포 산출물에 포함됐다고 판정하지 않는다. 디렉터리를 한 항목으로 접는 기본 `git status` 수치는 사용하지 않는다. |
| 관리자 EOD 조회·재시도 UI | **상세 소스 구현·live 미완료** | cycle·phase·대사·signal을 제어 테이블에서 읽고 원시 시뮬레이션 시각, cycle·최근 attempt 시작/완료/경과와 취소 뒤 반환된 매수 예약금·매도 예약수량을 표시한다. 05:30 readiness는 포트폴리오·거래일·시장·fence·가격·기업행사·regime·프로필 큐·runtime identity의 고정 10개 결과를 `stock_post_close_readiness_check`에 남기고 화면은 cycle PK의 최대 10행만 읽는다. 기업행사 현금·PRE_OPEN 변환 미완료 수도 각 readiness 실패 건수로 표시한다. 시장 CLOSED 상태에서 가장 오래된 전체시장 `FAILED` cycle의 현재 phase backoff만 해제하는 재시도 API·버튼이 존재하며 `DEFERRED` 정책 대기는 우회하지 않고 Job을 직접 실행하지 않는다. 정상 phase는 10초 coordinator가 자동 선점하므로 별도 수동 실행 명령을 의도적으로 제공하지 않고, 로그인 세션 브라우저 검증만 남았다. 강제 마감도 제공하지 않는다. |
| build/schema 실행 정체성 | 소스·배포 JAR 보강·현재 실행 불일치 확인 | build/schema version 기록과 시작 schema readiness를 구현했고, 로컬 Gradle build SHA는 현재 HEAD 뒤에 워킹트리 변경이 있으면 `-dirty`를 붙인다. CI의 content-addressed `BUILD_SHA`는 이를 그대로 우선한다. 2026-07-16 `bootJar`로 생성한 back `eacb2484517d-dirty`, batch `b26da0334f74-dirty` 산출물에는 신규 schema validator·session fence·coordinator·EOD API 클래스가 포함됨을 JAR entry로 확인했다. 다만 `-dirty`는 변경 내용 자체를 식별하지 않으므로 운영 승인 build는 여전히 모든 전달 파일을 commit한 SHA 또는 CI가 주입한 digest를 사용해야 한다. 실행 중인 두 서버는 7월 15일 15:12부터 IntelliJ `out/production` classpath를 사용하며 신규 EOD 클래스가 없었으므로 현재 health `UP`은 새 구현의 live 검증이 아니다. |
| cycle 로그 상관관계 | 소스 구현 | `StockBatchJobRunner`가 `cycleId`를 가진 native·lightweight EOD 실행 scope에만 MDC를 설정하고 이전 값을 복원한다. 로그 패턴에도 `cycle`을 추가했다. 일반 주문·체결 worker에는 MDC 생성이나 DB 접근을 추가하지 않았으며, 별도 heartbeat 스레드처럼 실행 문맥을 넘는 비동기 로그는 기존 job·lock 식별자를 사용한다. |
| Batch metadata 보존·archive | 소스 구현·운영 비활성 | 완료된 오래된 JobInstance만 기본 최대 25개씩 compact execution archive로 옮기는 경량 실행기를 추가했다. 실패·중단·최근 instance는 제외하고 instance별 한 transaction을 사용한다. purge는 기본 `false`이며 명시적인 job-name allow-list 없이는 기동 설정 자체를 거부한다. 운영 metadata DDL·보존일·allow-list 승인은 남았다. |
| EOD 상세 스냅샷 보존·archive | **정책 미확정·미구현** | cycle cohort 단위 bounded archive/purge 원칙만 문서화했다. 회계·이의제기 보존일과 분석 저장소가 정해지지 않아 상세 스냅샷 자동 삭제는 추가하지 않았으며, 정규장 또는 무제한 cascade purge도 제공하지 않는다. |
| 강제 전체 마감 workflow | **의도적 미구현** | 일반 전체 마감 API enqueue는 `AFTER_CLOSE`에서만 허용하고 batch launcher도 `REGULAR` 실행을 거부한다. 장후 생성된 신호의 장애 복구만 `PRE_OPEN`에서 원래 요청 기준(전체시장은 거래일·cycle, 종목은 거래일·epoch·cycle)으로 재개한다. 고권한·사유·2단계 확인·감사 원장을 갖춘 별도 강제 workflow는 아직 없으며, 안전하지 않은 임시 `force` 플래그를 노출하지 않는다. |
| 정산 shadow 비교 | **bounded 진단 소스 구현·운영 미실행** | `db/diagnostics/stock_eod_settlement_shadow_mysql.sql`이 첫 `PORTFOLIO_SETTLED` cycle에서 기존 실시간 산식·frozen 산식·저장 결과를 계좌 PK 200개씩 읽기 전용 consistent snapshot으로 비교한다. 시장 OPEN, phase 선점 중, 다음 overnight phase 진입 후에는 fail-closed한다. 실제 운영 ALTER 후 첫 cycle의 전 계좌 페이지 결과 승인만 남았다. |
| 운영 MySQL 검증 | **미완료** | Docker 부재 시 명시적 `mysqlTest`가 초기화 단계에서 실패하도록 fail-closed로 보강했다. 현재 환경에서는 컨테이너가 시작되지 않아 실제 테스트 SQL이 실행되지 않았다. 실제 `EXPLAIN ANALYZE`, 18시 경합, 재시작, 현재 추정 주문 151만·체결 103만 이상 데이터 A/B가 남았다. |
| 운영 ALTER·재기동 | **미완료** | 2026-07-16 읽기 전용 실측에서 EOD 핵심 테이블과 신규 signal/portfolio 컬럼이 모두 미적용이고 배치 heartbeat는 실행 중이었다. 사용자가 백엔드·배치 종료를 확인한 뒤에만 적용한다. 현재 파일 존재와 H2 통과는 실 DB 적용 증거가 아니다. |
| `business_effective_at`·`recorded_at` 분리 | **의도적 보류** | 현재 hot ledger에는 두 컬럼이 없다. 주문·체결 INSERT 열과 DDL을 늘리는 변경이므로 실제 MySQL A/B 없이 추가하지 않는다. 현 단계 정확성은 거래일·epoch·fence와 동결 순서로 보장하고, 두 시각의 감사 필요성과 쓰기 회귀를 함께 검증한 뒤 별도 migration으로 판단한다. |

코드 증가 감사 결과도 다음처럼 해석합니다.

- 새 production class는 Spring component/entity scan, 명시적 주입 또는 화면 import 경로 중 하나로 연결되어 있으며, 이름 검색 기준의 명백한 고아 파일은 발견하지 못했다.
- production 이름 참조를 다시 세어 선언 파일 하나만 잡힌 클래스는 batch의 `StockSchemaReadinessValidator`, `ExecutionAccountDaySummaryScheduler`, `PostCloseCoordinatorScheduler`와 back의 `StockSchemaReadinessValidator` 네 개뿐이었다. 두 validator는 Spring이 직접 호출하는 `@Component ApplicationRunner`, 두 scheduler는 Spring이 직접 호출하는 `@Scheduled` 진입점이므로 Java 소스의 명시적 호출이 없는 것이 정상이며 고아 코드로 삭제하지 않는다.
- 감사 중 repository·서비스 사용 없이 JPA metamodel에만 등록되던 `StockMarketBusinessState`, `StockMarketSessionFence`와 전용 ID/enum 네 파일은 제거했다. 해당 테이블의 권위 있는 접근은 기존 `JdbcClient` 기반 fence/cycle 서비스로 일원화해 중복 모델을 남기지 않았다.
- `PostClosePhaseAttemptJobExecutionListener`는 native JobExecution ID 연결, `PortfolioSettlementJobExecutionListener`는 실패한 정산 claim 보상이라는 서로 다른 책임이므로 중복이 아니다.
- 현금흐름·기업행사·시세·빈 보유·regime·프로필 큐의 호환 scheduler는 기본 coordinator 모드에서 runtime-control·JobRepository·업무 테이블 조회 전에 반환한다. 반면 `PortfolioSettlementScheduler`는 18시 동결·정산 prefix의 현재 권위 실행기이므로 기본 모드에서도 유지한다. 운영 안정화 뒤 제거 후보는 coordinator가 대체한 호환 scheduler에만 한정한다.
- 제거한 네 JPA 타입이 `ddl-auto=create-drop` 테스트에서 우연히 맡던 테이블 생성 책임은 `StockTestEodSchemaInitializer`로 명시적으로 옮겼다. 첫 전체 회귀에서 이 책임 누락으로 H2의 `stock_market_business_state`·`stock_market_session_fence`가 생성되지 않는 실패를 재현했고, 테스트 전용 초기화기에 canonical과 같은 두 테이블·fence 상태 인덱스를 추가한 뒤 최신 백엔드 전체 490건을 다시 통과시켰다. 운영 스키마 검증은 JPA metamodel이 아니라 canonical DDL과 `StockSchemaReadinessValidator`가 담당하므로 중복 JDBC/JPA 모델을 복구하지 않는다.
- 야간 account-day summary 재구축에 전달되면서 사용되지 않던 `closeRunId` 인자는 제거했다.
- 포트폴리오 정산에 전달되지만 어느 Step에서도 읽지 않던 `enforceClose` Job parameter와 상수·테스트 기대값을 제거했다. 일반 전체 마감의 장후 이중 guard와는 무관한 무효 파라미터였다.
- signal 검증기에서 선언만 되고 분기·검증에 사용되지 않던 `FULL_MARKET_SIGNAL` 상수를 제거했다. 전체 마감 검증은 signal context와 launcher의 장후 guard가 담당한다.
- 사용자 주문 취소·부분취소·정정이 소유 주문과 fence를 한 SQL로 확인하는 `acquireOwned...` 경로로 전환된 뒤 production 호출이 0이던 `TradingSessionFenceService.acquireOpenMutationSession`과 전용 timer·자기호출 테스트를 제거했다. 사용 중인 신규 주문·소유 주문 mutation SQL과 lock 순서는 바꾸지 않았고, REGULAR 시각을 검사하지 않던 옛 대체 경로가 미래에 재사용될 위험만 없앴다.
- bulk 경로 전환 뒤 호출이 0이 된 단건 자동주문 INSERT wrapper, 단건 배당 현금흐름·권리 납입 writer, 미사용 account-ledger 조회와 summary discard 공개 wrapper를 제거했다. 실제 fallback과 portable SQL에서 사용하는 단건 helper는 유지했다.
- 기업행사 `CASH`·`PREOPEN_SECURITY_TRANSFORMS` Job이 단계별 Step 진입점으로 전환된 뒤 운영 호출이 0이고 테스트 세 건만 사용하던 `applyDueCorporateCashActions(...)`, `applyDuePreOpenSecurityTransforms(...)` public 오케스트레이션도 제거했다. 테스트는 실제 Job과 같은 현금 Step·검증 Step 및 PREOPEN 6-Step 순서를 직접 호출하도록 바꿨다. 호환 `ALL` Job이 실제 사용하는 `applyDueCorporateActions()`는 유지한다. 이 정리는 production 중복 분기만 줄이며 정규장 주문·체결 SQL·잠금·commit 수에는 변화가 없다.
- 전체 회귀에서 확률적 추가 주문이 SELL을 선택할 때 무보유 계좌라 정상 탈락하던 프로필 비교 테스트를 발견했다. 생산 랜덤 로직을 고정하지 않고 비교 계좌 모두에 현금·보유 fixture를 제공해 방향과 무관하게 주문 활동 강도만 비교하도록 수정했으며, 동일 테스트를 5회 연속 재실행해 통과시켰다.
- 관리자 종목 수급 `ALL`은 전 기간 `stock_execution`을 직접 집계하지 않는다. 완료된 과거 거래일은 `stock_order_book_daily_snapshot`, 현재 거래일만 `[dayStart, now)` 원장 범위를 사용한다. 이 변경은 주문·체결 INSERT, 인덱스, 잠금에 영향을 주지 않는다.
- 파일 크기 감사에서는 `MarketCloseRolloverWriter` 1,667행, 중복 오케스트레이션 제거 후 `CorporateActionService` 1,953행, `MarketSessionFenceService` 1,249행으로 책임 밀도가 높은 클래스가 확인됐다. 이름 검색·Spring wiring·호출 경로 기준으로 이 안의 SQL·청크 cursor·멱등 처리·대사 로직을 사장 코드로 판정할 근거는 없지만, 세 클래스 모두 유지보수 부채다.
- 2026-07-16 시간대 coordinator·기업행사·정기 현금흐름·signal 재감사에서도 기본 coordinator가 대체한 호환 scheduler는 runtime control과 JobRepository 전에 반환하고, `POST_CLOSE` scheduler는 1 thread, native Job과 cycle 소속 lightweight task는 전역 heavy-admission 1개로 직렬화되는 것을 다시 확인했다. 기업행사는 due event 기본 25건·계좌 기본 200건, 정기 현금흐름은 계좌 기본 200건, signal claim은 poll당 기본 20건으로 상한이 있다. 이 제어 경로는 정규장 `stock_order`·`stock_execution`을 읽지 않는다.
- 정규장 거래 경로의 fence도 다시 대조했다. 사용자 신규 주문과 소유 주문 변경, 자동 참여자 주문 청크, 상장주관사 주문, 실제 체결 반영은 각각 업무 트랜잭션 안에서 종목 fence와 소형 market-config/business-state/simulation-clock 문맥을 **한 SQL**로 읽고 fence 행만 공유 잠금한다. cycle·attempt·readiness·snapshot 조회는 추가하지 않는다. 다만 이 한 번의 원격 DB 왕복과 공유 잠금 비용까지 0이라고 주장하지 않으며, 현재 주문·체결량 기준 MySQL p95/p99 A/B 전에는 거래량 비회귀를 운영 승인하지 않는다.
- 이 대형 클래스를 운영 ALTER·MySQL A/B 전에 단순히 파일 수를 늘려 분해하지 않는다. 지금의 기계적 분리는 런타임 SQL·트랜잭션 경계는 줄이지 못하면서 검증 diff만 키워 주문·체결 회귀 원인 추적을 어렵게 한다. 실 DB A/B와 첫 운영 cycle이 안정화된 뒤에만 SQL과 트랜잭션 동작을 바꾸지 않는 별도 리팩터링으로 `MarketCloseRolloverWriter`를 주문 캡처·예약 반환·불변 스냅샷·대사/지표, `CorporateActionService`를 현금 단계·PRE_OPEN 변환·검증/오케스트레이션, `MarketSessionFenceService`를 거래 승인 gate·거래일 전환·저빈도 원장 변경 허가 책임으로 나눈다.
- 위 후속 분리는 새 추상화 수나 파일 수 자체를 목표로 하지 않는다. 기존 public 호출 계약, 잠금 순서, SQL 문장 수, 청크 상한, 커밋 수와 p95/p99가 같거나 개선됐다는 회귀 증거가 승인 조건이며, 런타임 비용을 늘리는 공통 인터페이스·reflection·추가 이벤트 계층은 도입하지 않는다.

DDL 경로는 `stock-back-service/src/main/resources/db/ddl/stock_all.sql`과 같은 디렉터리의 ALTER가 canonical입니다. 배치 MySQL migration 테스트도 이 경로를 직접 읽습니다. 두 서비스에 공통으로 남아 있는 ALTER 사본은 byte 동일하며, 백엔드에만 있는 `stock_market_report_daily_summary_alter.sql`과 `stock_market_turnover_normalization_alter.sql`을 배치에 다시 복제하지 않습니다.

### 2026-07-16 소스 구현 재감사 상태

현재 소스에는 다음 기반 작업이 반영되어 있습니다.

- 종목별 session fence와 사용자 주문·정정·자동 주문·상장주관사 주문·체결 최종 검증
- `READY_TO_OPEN`은 단계 이름만으로 통과하지 않고 `PENDING`, 최종 `COMPLETED`는 `COMPLETED` 상태까지 일치할 때만 개장 자격으로 인정하며, 거래일 singleton 행이 없거나 원시 날짜·준비 날짜가 어긋난 경우도 소형 제어 테이블에서 fail-closed 처리
- AFTER_CLOSE 진입 시 full-market `CLOSE_REQUESTED` cycle을 먼저 확정한 뒤 종목 fence를 닫아, cycle 생성 전 저빈도 현금·계좌 변경이 계좌 스냅샷 기준시각을 침범하던 admission 공백을 제거
- 거래일별 논리 cycle, phase attempt, lease/CAS와 수동 신호 실행 기준 고정. 모든 신호는 요청 거래일을, 종목 신호는 session epoch까지 보존한다. 요청 시점에 이미 존재하는 cycle은 `expected_cycle_id`도 고정하고, 아직 없으면 `(business_date, scope_type, scope_key)` 유일 제약으로 첫 실행이 만든 동일 cycle을 이후 재시도에서 재사용한다.
- 신호 claim lease, 실행 중 30초 heartbeat, backoff, `DEFERRED`/`DEAD_LETTER`, 선두 지연 제거. heartbeat는 `stock_batch_job_signal`의 정확한 PK와 `claim_token` 한 행만 갱신하고 주문·체결·계좌·보유 원장을 읽지 않으므로, 180초를 넘는 수동 작업도 중복 claim을 막으면서 정규장 부하를 만들지 않음
- close cycle 기준 계좌·가격·보유·취소 전 주문 cohort 스냅샷 DDL/H2/초기화 계약
- 미체결 주문의 set-based 캡처·취소·예약금/예약수량 반환과 대사
- 실시간 운영 테이블을 읽지 않는 frozen snapshot 기반 포트폴리오 정산
- 정산 완료 시 기존 frozen cohort 조인 한 번 안에서 `PortfolioSnapshotProcessor` 산식을 다시 적용하고, 입력 hash·버전·품질 상태와 함께 현금·평가액·총자산·수익률·보유수량·예약매도·포지션 수를 저장 결과와 전부 대조. 별도 테이블 스캔·shadow write 없이 잘못 저장된 결과의 phase 전진을 차단
- 포트폴리오 Writer의 현재 시뮬레이션 시각 fallback API를 제거하고 Job이 전달한 동결 거래일·스냅샷 시각만 허용하여, 지연 실행·재시작 시 실시간 시각이 결과에 섞이는 경로를 차단
- 포트폴리오 Writer의 MySQL 저장은 Connector/J batch 재작성에 의존하는 계좌별 `batchUpdate`가 아니라 최대 500행의 명시적 `INSERT ... AS incoming ON DUPLICATE KEY UPDATE`로 처리. 기본 200계좌 Spring Batch chunk에서는 원격 정산 쓰기 1회이며 더 큰 설정도 500행 SQL로 나누되 동일 Step transaction에서 커밋
- 계좌 스냅샷 청크 사이의 관리자 입출금·계좌 생성/탈퇴·자동 참여자 초기자금·유상증자 청약·신규 종목 상장 경합을 막는 저빈도 account-ledger freeze guard. 이 경로들만 거래일 제어행을 공유 잠금하고, 먼저 시작한 변경은 장마감이 기다려 포함하며 `LEDGER_FROZEN` 전 새 변경은 계좌 row 잠금 전에 거부한다. 사용자 주문·자동 주문·체결 hot path에는 이 전역 guard를 넣지 않는다.
- 배치 자동 월급·정기 입금은 외곽 스케줄 검증만 신뢰하지 않고 200계좌 `REQUIRES_NEW` 청크마다 동일 guard를 다시 획득한다. 장마감이 청크 사이에 시작되면 다음 청크가 계좌 UPDATE 전에 중단되어 계좌 스냅샷의 기준시각을 침범하지 않으며, 이 추가 조회는 야간 현금 경로에만 존재한다. 각 청크의 현금·현금흐름 반영과 실행 cursor 전진도 한 트랜잭션이므로 후반 청크 실패 뒤 같은 Spring Batch JobInstance를 재시작해도 이미 완료한 계좌 범위를 다시 지급하지 않는다.
- 장마감 때 계좌별 보유 평가액·총수량·예약매도·포지션 수를 한 번 집계해 `stock_close_account_snapshot`에 고정하고, 정산 paging은 보유 전체 `GROUP BY`를 페이지마다 반복하지 않는 account-snapshot point/range read 구조
- `settlement_eligible_at` 이전 정산 연기와 실패 시 cycle claim 즉시 해제
- 단일 전용 executor에서 한 phase씩만 실행하는 00:00/04:30/05:30 coordinator와 개장 readiness
- coordinator 활성 시 기존 현금흐름·기업행사·시세·빈 보유·regime·프로필 큐 독립 scheduler를 runtime-control 조회 전부터 즉시 반환시켜 동일 야간 작업의 이중 실행과 DB pool 중첩을 차단
- PRE_OPEN 프로필 큐는 Redis 전용 key를 단일 Lua로 정확히 교체하고 저장된 distinct profile 수가 DB schedule 결과와 일치할 때만 성공한다. Redis 장애·부분 반영을 `AUTO_MARKET_PREPARED` 성공으로 숨기지 않으며, 정규장 10분 best-effort 복구와 주문 생성 5초 경로에는 strict 교체나 추가 DB 조회를 넣지 않음
- 기업행사 현금 단계와 PRE_OPEN 가격·수량 변환 단계 분리 및 action processing ledger
- 기본 coordinator의 기업행사 `CASH` operation은 배당 지급 → 자동청약 → 납입/권리 만료 → 후조건 검증 4개 Step, `PREOPEN_SECURITY_TRANSFORMS`는 권리락 → 유상 신주 → 무상/주식배당 → 분할 → 상장폐지 → 후조건 검증 6개 Step으로 분리. Step metadata 증가는 00시 이후 단일 heavy-admission 안에서만 발생하며, 18시 close-critical Job은 frozen cohort의 업무 checkpoint가 재시작을 담당하므로 단일 Spring Batch Step을 유지해 원격 metadata commit 지연을 추가하지 않음
- 자동 월급이 정책상 비활성인 경우 야간 현금 phase를 0건 정상 완료로 전진시키며, 수동 월급 신호는 `PORTFOLIO_SETTLED` 전에는 claim/attempt를 소비하지 않는 cycle-index gate. 백엔드가 `eligible_at=T+1 00:00`으로 저장한 신호는 배치 실행기가 `PRE_OPEN`에서도 허용하고 `REGULAR`에서만 metadata 생성 전에 연기하므로, 00시가 지나 `AFTER_CLOSE`가 끝났다는 이유로 영구 대기하지 않음
- 당일 체결 전체 집계를 18시 경로에서 제거한 종목별 야간 보고서 Step
- 종목 야간 OHLC 보고서의 체결 전체 `ROW_NUMBER()` 정렬 두 개를 제거하고, 기존 `(source, symbol, executed_at)` 범위 인덱스 1회 집계와 `(source, symbol, side, executed_at, id)` 캔들 인덱스의 최초·최종 체결 `LIMIT 1` 조회로 전환. 계좌 보고서는 기존 market-report-flow 인덱스를 명시 사용하며 신규 `stock_execution` 인덱스는 추가하지 않음
- 전체시장/종목 겸용 nullable predicate SQL을 분리하여 종목 마감·야간 종목 보고서는 `symbol = ?` exact predicate와 symbol 선두 인덱스를 사용
- bounded 비동기 계좌 요약 flush와 야간 durable 원장 기준 전량 재구축
- 직전 close cycle의 전역 cash-flow watermark 이후 PK 범위만 읽는 증분 현금흐름 스냅샷
- 계좌 스냅샷 500건 청크가 같은 현금흐름·청약 원장을 반복 훑지 않도록 저빈도 원장에 `(account_id, id)`·`(account_id, status)` 인덱스를 추가하고, 대사 cursor는 EOD 스냅샷의 `(close_cycle_id, reconciliation_status, account_id)`로 고정. 세 인덱스 모두 주문·체결 INSERT에는 영향을 주지 않으며 startup readiness가 누락을 차단
- 원시 시계가 앞선 경우 시장을 열지 않고 하루씩 기록하는 `SKIPPED` cycle 복구
- 원시 시계가 여러 날짜 앞선 복구에서는 AFTER_CLOSE 동기화가 원시 날짜의 close cycle을 만들지 않고 현재 `active_business_date`를 먼저 동결한다. `raw_simulation_date`는 마감 대상일로 되돌리지 않고 단조롭게 권위 시계를 따르며, PRE_OPEN에 active 거래일의 cycle 행이 없으면 그 거래일부터 정상 마감한 뒤 이후 누락일만 하루씩 `SKIPPED` 처리한다. 이 판단은 business-state/cycle 제어행만 읽고 주문·체결 원장에는 추가 접근하지 않는다.
- 장마감·정산 단계에서 한 번 갱신하는 `stock_post_close_cycle_metric` 요약과 주문·체결 원장을 읽지 않는 관리자 EOD 조회 API
- `/admin/system/eod` 전용 반응형 운영 화면과 해당 화면에서만 동작하는 15초 저주기 조회
- EOD 화면의 단계 시간표는 backend 기본값과 동일하게 PREOPEN 변환 04:30, 자동장 준비·readiness 05:30, cycle 완료는 06:00 실제 개장 확인 후로 표시한다. signal은 처리 건수 0을 대기 결과처럼 오해시키지 않도록 `eligible_at`, `next_attempt_at`, `attempt_count/max_attempts`를 함께 보여주며 이를 위해 추가 API나 주문·체결 집계를 호출하지 않는다.
- 배당·자동청약·신주반영·분할·상장폐지의 계좌/주문 bounded chunk와 계좌별 처리 원장
- 기업행사 due event 목록도 효력일·ID 순서의 기본 25건·최대 200건으로 제한하고, 남은 event는 incomplete 후조건을 통해 coordinator 재시도로 처리. 계좌 1만 개·200계좌 청크에서 이벤트 하나가 약 50개의 짧은 업무 트랜잭션을 만들 수 있음을 기준으로, 초기 검토안의 기본 200·최대 1,000 이벤트 상한을 채택하지 않아 한 Step이 야간 commit 대역폭을 장시간 독점하지 않게 함. 자동청약 완료 event는 processing ledger의 `ALL` 완료행으로 다음 후보에서 제외하여 batch 선두 고착을 방지
- 권리락 entitlement도 보유자 전체 단일 INSERT가 아니라 `(symbol, close_run_id, account_id)` 순서의 기본 200계좌 누락분 chunk로 생성하고 마지막 chunk 이후에만 action 상태·가격을 전환
- 현금배당은 계좌당 `UPDATE + cash-flow INSERT + entitlement UPDATE + processing INSERT`를 반복하지 않고, 200계좌 chunk마다 계좌 PK 오름차순 잠금 후 현금 CASE UPDATE·다중 cash-flow INSERT·권리 일괄 UPDATE·processing 다중 INSERT의 고정된 set-based SQL로 처리. 계좌 1만 개에서도 원격 DB 왕복이 계좌 수가 아니라 chunk 수에 비례하며 주문·체결 원장에는 접근하지 않음
- 주주배정·일반공모 자동청약도 후보 200계좌를 action 잠금 아래 처리한다. 계좌 현금은 PK 오름차순 한 번, 주주배정 권리는 entitlement PK 오름차순 한 번만 잠그고 JVM에서 정책 수량을 계산한다. 성공분은 현금 CASE 차감·권리 일괄 UPDATE 또는 일반공모 다중 INSERT·현금흐름 다중 INSERT로 반영하며, 성공·탈락 처리 원장도 한 번의 다중 INSERT로 기록한다. SQL 왕복은 계좌 수가 아니라 청크 수에 비례하고, 일반공모 잔량은 잠긴 action 기준으로 순차 배정하여 초과 청약을 막는다.
- 무상증자·주식배당은 200계좌 보유를 PK 순으로 잠근 뒤 CASE UPDATE 한 번으로 수량/평균단가를 반영하고, 유상증자 신주는 같은 chunk를 다중 `INSERT ... AS incoming ON DUPLICATE KEY UPDATE`로 UPSERT. 이후 권리와 processing 원장도 각각 일괄 반영하여 계좌당 세 번의 원격 왕복을 제거. MySQL 8.0.19+ row alias 문법은 [공식 INSERT 문서](https://dev.mysql.com/doc/refman/8.0/en/insert.html)를 기준으로 사용하며 H2 테스트는 별도 호환 경로를 유지
- 액면분할도 200계좌별 UPDATE/processing INSERT 루프를 제거하고, 보유 PK 오름차순 잠금·단일 `UPDATE ... account_id IN (...)`·processing 다중 INSERT 세 문장으로 처리. 수량·예약수량·평균단가와 계좌별 멱등 처리원장은 같은 chunk 트랜잭션에서 커밋
- 상장폐지 주문 정리는 잠금 없는 bounded 후보 뒤 `계좌 → 매도 보유 → 정확한 주문 PK` 순서로 잠가 상태 인덱스 `FOR UPDATE` gap lock을 제거
- 상장폐지와 수동 종목 취소는 잠긴 주문을 주문별 UPDATE하지 않고 최대 500개 정확한 PK를 한 번에 `CANCELLED`로 전환한다. 매수 예약금은 계좌별 합산 CASE UPDATE, 매도 예약수량은 동일 종목의 계좌별 CASE UPDATE로 각각 한 번만 반환하여, 500주문 청크의 원격 쓰기를 주문 수가 아니라 최대 3회로 제한한다.
- 계좌 탈퇴·자동 참여자 철회의 미체결 정리는 호출자가 계좌 PK를 먼저 잠근 상태에서 `PENDING`, `PARTIALLY_FILLED`를 각각 기존 `(account_id, status, created_at[, id])` 또는 `(market_type, status, account_id, created_at[, id])` 인덱스 순서로 최대 500건씩 keyset 조회한다. 후보·매도 보유·정확한 주문 PK·취소/반환을 한 청크씩 처리하므로 계좌의 전체 미체결 주문을 JVM에 적재하거나 무제한 `IN` 절을 만들지 않는다. `stock_order` 신규 인덱스는 추가하지 않는다.
- 정규장 자동 주문 만료도 기본 100개 후보를 계좌 PK·실제 보유 복합키·정확한 주문 PK 순으로 각각 한 번 잠그고, 주문 일괄 취소·매수 예약금 CASE 반환·매도 예약수량 CASE 반환으로 처리한다. 설정은 1~1,000만 허용하고 범위를 벗어나면 서버 기동을 거부한다. 이 경로는 기존 주문 상태/PK 인덱스만 사용하고 신규 `stock_order` 인덱스를 추가하지 않으며, 주문별 취소·계좌별 반환 SQL을 제거해 체결 worker와의 DB 왕복 경쟁을 줄인다.
- 정규장 자동 주문 생성은 참여자 청크의 고유 계좌 상태를 PK 오름차순 한 번, 실제 매도 보유 `(account_id, symbol)`을 오름차순 한 번 잠가 읽는다. JVM은 잠긴 상태에서 계좌별 총 매수 예약금과 보유별 총 매도 예약수량을 판정하고, 성공 집합만 계좌 CASE UPDATE 1회·보유 CASE UPDATE 1회·주문 multi-row INSERT 1회로 반영한다. 기존 계좌/보유별 예약 UPDATE 루프와 드라이버 재작성 여부에 따라 주문 수만큼 전송될 수 있던 `batchUpdate`를 제거하여 한 청크의 원격 쓰기 횟수가 참여자·계획 주문 수에 비례하지 않게 했다. 참여자 청크 1~100·종목별 due 후보 1~500의 기동 상한과 multi-row INSERT 800행 상한을 함께 강제한다.
- 자동 월급 후보도 전체 계좌 JVM 적재 대신 계좌 PK keyset 200건씩 읽고, 최근 지급 원장은 해당 chunk 계좌만 조회
- 자동 월급·정기 입금의 실제 지급도 계좌마다 `account UPDATE + cash-flow INSERT`를 반복하지 않고, 지급 대상 200계좌를 PK 오름차순으로 잠근 뒤 CASE 현금 UPDATE와 다중 cash-flow INSERT를 각각 한 번만 실행. 선택·잠금·갱신·원장 건수 불일치 시 chunk 전체를 롤백하여 계좌 수 증가가 원격 왕복 수로 선형 증폭되지 않게 함
- PRE_OPEN 외부 시세 provider 호출을 DB 트랜잭션 밖으로 이동하고, 성공한 종목의 가격·tick 쓰기만 종목별 짧은 `REQUIRES_NEW`로 커밋. 대상 주문도 `VIRTUAL_PRICE`로 한정하여 주문장 원장 스캔을 제거
- 정규장 시세 refresh는 기존 부분 성공과 Redis 장애 격리를 유지하되 close cycle로 호출된 PRE_OPEN refresh는 provider·검증·DB 갱신·Redis publish 실패 종목을 모두 시도한 뒤 phase 전체를 실패 처리하여 일부 가격/캐시만 준비된 상태로 `MARKET_DATA_PREPARED`에 진입하지 않음. 성공 종목의 재시도는 기존 `(symbol, price_time, id)` 범위를 사용한 `NOT EXISTS` 멱등 INSERT로 같은 tick 중복 쓰기를 막으며 `stock_order`·`stock_execution`에는 신규 인덱스나 쓰기를 추가하지 않음
- 기업행사 현금/PRE_OPEN phase의 종료 조건과 개장 readiness를 기업행사·processing ledger 소형 집계로 검증하고, 미처리 action이나 동결 스냅샷 누락을 성공으로 넘기지 않는 fail-closed 후조건
- 전체 장마감 Redis lock 대상 조회에서 `stock_order`·`stock_holding` UNION을 제거하고 session fence·시장 설정·상장 종목 제어 테이블만 사용
- 정산 paging 전용 `(close_cycle_id, settlement_target, account_id)` 인덱스를 EOD 스냅샷 테이블에만 추가하고, 주문·체결 hot ledger에는 EOD 인덱스를 추가하지 않음
- 15초 관리자 EOD polling의 오래된 미완료 cycle과 최근 signal 조회는 저빈도 제어 테이블 전용 `(scope_type, scope_key, status, business_date, id)`, `(expected_cycle_id, id)` 인덱스로 고정. 이 인덱스는 `stock_order`·`stock_execution` 쓰기 증폭을 만들지 않음
- 주문 API와 배치 자동주문·체결의 session-fence 시간을 각각 고정 태그 Micrometer timer로 계측하고 symbol 고카디널리티 태그는 사용하지 않음
- build SHA 생성, schema version 기록, 필수 EOD 테이블·컬럼·인덱스의 시작 시 readiness 검증
- immutable snapshot과 signal lease ALTER도 `information_schema` 조건부 DDL로 전환하여, 컬럼 추가 후 보정/인덱스 단계에서 중단돼도 이미 성공한 DDL을 다시 충돌시키지 않고 같은 스크립트를 재개할 수 있음
- native Job과 lightweight EOD task 모두 cycle lease를 heartbeat하여 처리량 증가로 180초를 넘겨도 다른 노드가 같은 phase를 중복 실행하지 않게 함
- coordinator가 소유한 native Job은 `beforeJob`에서 현재 RUNNING phase attempt에 실제 Spring Batch `JobExecution` ID를 연결한다. `cycleId`가 없는 정기 Job에는 DB 조회 자체를 추가하지 않고, close·settlement는 기존 첫 Step claim 시점의 명시적 연결을 유지하여 18시 hot path에 중복 제어 조회를 만들지 않음
- 모든 native Spring Batch Job과 cycle ID를 가진 lightweight EOD task는 job별 lock보다 먼저 공통 heavy-admission lease를 획득하고 함께 heartbeat한다. 따라서 POST_CLOSE와 maintenance/signal scheduler가 서로 다른 스레드여도 보고서·기업행사·정산·수동 현금·장마감 같은 무거운 작업이 동시에 DB pool과 commit 대역폭을 점유하지 않으며, admission을 얻지 못한 작업은 JobRepository metadata를 만들기 전에 `SKIPPED/DEFERRED`됨. 정규장 체결·자동 주문 경량 task에는 적용하지 않음
- job lock과 heavy-admission의 owner는 물리 실행마다 UUID token으로 분리한다. 같은 batch JVM에서 180초 lease가 만료된 뒤 새 실행이 lock을 회수해도 이전 실행의 heartbeat와 `finally` release는 owner 조건이 달라 새 lock을 갱신하거나 삭제하지 못한다. 단발성 heartbeat DB 오류는 다음 성공 갱신에서 해소하고, 업무 결과 직전 최종 owner 검증으로 실제 소유권 상실만 실패 처리한다. 이 검증은 `stock_batch_job_lock`과 cycle 제어행만 사용한다.
- 수동 signal의 180초 claim도 30초 heartbeat로 정확한 signal PK·claim token 한 행만 연장한다. 일시 DB 오류 뒤 다음 주기 갱신을 계속하고 업무 종료 직전 claim을 최종 확인하며, 소유권을 잃은 실행은 stale token으로 결과 상태를 덮어쓰거나 그 실패 기록 때문에 signal poll 전체를 중단하지 않는다.
- heartbeat scheduler가 종료·포화되어 heartbeat 등록 자체가 실패하면 업무 Job을 시작하지 않고 job lock과 heavy-admission lock을 즉시 모두 반환한다. TTL 만료까지 제어 lock이 남아 다음 야간 phase를 불필요하게 막지 않으며, 이 실패 경로도 JobRepository의 무거운 업무 실행 전에 종료된다.
- 종료 시 미실행 delayed/periodic trigger를 취소하고 이미 시작된 업무만 기다리는 scheduler 종료 정책
- 상시 체결 worker는 원시 세션·DB 시장 상태·runtime control을 1초 단위로 한 번만 합쳐 확인하고, worker 수만큼 동일 gate SQL이 증가하지 않게 한 공유 캐시
- 자동 주문 생성 청크는 BUY·SELL 전체 고유 계좌를 한 번의 PK 정렬 조회로 먼저 잠근다. SELL-only 청크가 보유를 먼저 잠근 뒤 주문 INSERT의 부모 계좌 FK 확인을 기다리는 잠금 역전을 제거하면서, 추가 왕복은 주문 건수가 아니라 청크당 1회로 제한
- 체결 후보가 선택된 뒤 정확한 매수·매도 주문 PK 두 건은 `FORCE INDEX(PRIMARY)`와 ID 오름차순 `FOR UPDATE`로 재검증한다. 정확한 거래 원장 잠금의 `SKIP LOCKED`를 제거하여 이미 선택된 최우선 후보가 잠깐 경합했다는 이유로 다음 fallback 주기까지 누락되는 지연을 막음
- 장마감·수동 중지의 정확한 주문 PK 잠금 뒤 실행하는 취소 UPDATE도 MySQL `FORCE INDEX(PRIMARY)`로 고정한다. 상태 보조 인덱스 실행계획으로 변해 신규 주문 INSERT와 gap-lock 경합을 만드는 위험을 줄이되 SQL 횟수와 주문 인덱스 수는 늘리지 않는다.
- 체결의 매수·매도 계좌 잠금도 계좌별 두 번의 원격 조회가 아니라 `id IN (?, ?)`와 PK 오름차순을 사용한 한 번의 `FOR UPDATE`로 통합한다. 잠금 순서는 유지하면서 체결 한 건당 DB 왕복을 1회 줄이고, 두 계좌 중 하나라도 사라진 비정상 상태는 원장 변경 전에 실패 처리
- 테스트 프로필에서 post-close coordinator와 계좌 일일요약 background scheduler를 명시적으로 끄고, 두 핵심 scheduler가 모두 비활성일 때 시장 세션 동기화 자체를 실행하지 않아 테스트 DB의 우발적 close cycle·fence 변경을 차단
- 체결의 잠금 없는 단일 후보 쿼리는 `TransactionTemplate` 밖에서 실행하고 실제 교차 후보가 있을 때만 business transaction과 종목 session fence 공유 잠금을 시작. fence 이후에는 계좌·보유·정확한 주문 PK 순으로 잠근 뒤 상태·가격·계좌를 재검증하므로 cutoff 정확성은 유지하면서, 미체결 probe가 빈 COMMIT·fence DB 왕복·장마감 drain 대기를 만들지 않음
- 체결 chunk가 0건이면 동일 symbol의 `hasExecutablePair`를 즉시 다시 조회·재등록하지 않음. 신규/변경 주문의 after-commit enqueue 또는 저주기 fallback이 다음 기회를 만들며, 체결이 1건 이상 성공한 chunk만 잔여 교차 쌍을 확인해 재등록하므로 가격 미교차 symbol의 이중 조회와 Redis hot loop를 차단
- 10초 세션 동기화는 이미 정합한 REGULAR/PRE_OPEN/AFTER_CLOSE 상태를 소형 config/fence 읽기로 판정하고 즉시 반환하여, 정상 장중 모든 종목 fence를 반복 `FOR UPDATE`·UPDATE하지 않는 read-only fast path
- 자동 주문·주문 만료·상장주관사 공급·fallback 체결 scheduler는 작은 시장 설정/fence/business-state gate가 닫혀 있으면 주문 후보나 계좌를 읽기 전에 즉시 반환
- 자동 주문 한 번의 run에서 읽은 시뮬레이션 시각을 모든 profile/symbol shard의 배당 신호 기간 계산까지 전달하여, 거래량·종목 수에 비례하던 `stock_simulation_clock` singleton 재조회를 제거
- ready-profile 정합화는 DB에 실제 활성 스케줄이 있는 프로필만 권위 집합으로 유지하고 비활성·삭제 프로필을 Redis에서 단일 `ZREM`으로 제거하며, 실패 lease가 남은 프로필은 1초 재등록 대신 실제 lease 만료 시각까지 대기하여 빈 후보 조회가 worker 슬롯과 DB 연결을 소모하지 않게 함
- coordinator와 정산 lifecycle 양쪽에서 열린 시장을 이중 차단하여 수동/내부 직접 호출도 정규장 스냅샷 검증·정산 쿼리를 실행하지 않게 함
- 정산 launcher도 full-market cycle이 정확히 `LEDGER_FROZEN`인지 JobRepository 기록 전에 확인하여, 잘못된 직접 호출은 스냅샷 무결성 집계와 Spring Batch metadata 쓰기를 시작하지 않음
- 야간 보고서 Step의 바깥 트랜잭션을 resourceless로 바꾸고 실제 symbol/account 집계만 짧은 `REQUIRES_NEW`로 커밋하여 유휴 business connection 점유를 제거
- 종목별 야간 보고서가 중간 커밋된 상태에서는 조회 API가 결과를 노출하지 않고, `stock_post_close_cycle.close_run_id` 인덱스 조인으로 phase가 `REPORTS_AGGREGATED` 이상인 run만 읽도록 하여 부분 보고서 공개를 차단. 이 guard는 소형 cycle row만 추가 조회하고 주문·체결 쓰기 경로에는 들어가지 않음
- 야간 보고서의 대상 종목도 `(close_cycle_id, symbol)` 키셋 기본 25건·최대 200건의 체크포인트 cohort로 읽고, 종목별 집계만 짧은 `REQUIRES_NEW`로 실행한다. 장애 시 원본 체결 재조회는 최대 한 cohort로 제한하고 종목마다 Batch metadata COMMIT을 만드는 쓰기 증폭은 피하여 종목 수 증가가 무제한 JVM 목록·장기 트랜잭션·DB connection 독점으로 번지지 않게 함
- 포트폴리오 정산의 200계좌 chunk가 중간 커밋되어도 계좌 이력·수익률 순위·관리자 총자산 이력은 `PORTFOLIO_SETTLED` 이상인 full-market cycle만 노출. 기존 migration 전 snapshot은 cycle/run ID가 모두 `NULL`인 경우에만 legacy 확정 데이터로 허용하며, 조회 guard는 `portfolio_snapshot`의 기존 계좌/날짜 인덱스와 cycle PK 조인만 사용하고 주문·체결 원장에는 접근하지 않음
- 포트폴리오 완료 Step은 결과 행 존재 여부만 세지 않고 frozen account 입력으로 SHA-256을 다시 계산해 `close_run_id`, `input_hash`, `calculation_version`, `data_quality_status`를 정확히 대조한다. 이 검증은 정산 종료 시 account/portfolio snapshot 인덱스를 한 번 순회할 뿐 `stock_order`·`stock_execution`을 읽지 않는다. metric 없는 `COMPLETED` 우회도 build/schema 식별값이 모두 없는 실제 migration legacy cycle에만 허용한다.
- 수동 시간 이동 완료 판정에서 현재 활성 계좌와 `portfolio_snapshot`을 다시 세던 조회를 제거하고, full-market cycle 유일키와 `stock_post_close_cycle_metric` 한 행만 읽는 O(1) 판정으로 전환. 계좌·정산 이력이 늘어도 관리자 clock 조회가 거래 DB 부하로 확대되지 않으며, `다음 일자 00:00`은 원장 동결·정산 완료, `다음 장 06:00`은 야간 후처리·개장 readiness 완료를 각각 별도로 요구
- 체결 계좌 일일요약 flush와 야간 정확 재구축이 같은 전역 락을 사용하며, 락 획득·재구축·해제를 각각 짧은 독립 트랜잭션으로 분리해 flush connection 대기를 방지
- 완료된 일자의 늦은 인메모리 누적값 폐기와 account-day map의 조건부 제거를 적용하여 야간 재구축 뒤 중복 합산과 체결 건수 비례 queue 순회 비용을 차단
- 장중 계좌 일일요약은 체결 건별 객체를 보관하지 않고 `(거래일, 계좌)`별 불변 delta를 `ConcurrentHashMap.compute`로 병합한다. 같은 계좌의 체결 10,000건이 한 flush 사이에 발생해도 pending 슬롯·UPSERT row는 1개이며, flush 중 새 delta와 실패분 재병합은 compare-remove/compute로 유실 없이 분리한다. 고유 계좌 슬롯이 설정 상한을 채운 경우에만 저카디널리티 `overflow`/`requeue-overflow` 지표를 남기고 체결 처리와 JVM 메모리를 보호한다.
- 관리자 시장 요약의 2초 `stock_execution` 당일 범위 COUNT를 제거하고 기존 `stock_execution_account_day_summary` 날짜 PK 범위로 전환. 집계는 기본 30초 flush만큼 지연될 수 있음을 화면에 명시하며, 미체결 주문 수는 기존 covering 상태 인덱스를 사용하되 관리자 요약 주기를 10초로 낮춤
- 공급·수요 화면의 자동 참여자 당일 체결 수도 `stock_execution`과 자동 참여자 계좌의 반복 범위 집계에서 계좌 일일요약의 날짜 PK 범위 조인으로 전환하고, 자동시장 상태 기본 갱신을 2초에서 10초로 조정. 활성 프로필·regime·열린 자동 주문처럼 작은 설정/열린 cohort만 같은 저주기 응답에 남기며 화면에 정상 flush 시 약 30초 요약 지연과 장애·재기동 시 야간 원본 대사로 확정된다는 점을 표시
- 종목 거래요약·캔들 원장 집계에는 서버 인스턴스별 bounded single-flight 읽기 캐시(기본 10초, 키 1,000개)와 5초 read-transaction timeout을 적용. 동일 종목을 여러 화면·사용자가 폴링해도 TTL 안에는 원본 `stock_execution` 집계 한 번만 실행하고, 캐시 실패는 저장하지 않아 다음 요청이 재시도한다. 프론트의 거래요약·1분 캔들은 10초, 5분 캔들은 15초, 15분·1시간 캔들은 30초로 조정하고 모든 React Query background refetch를 기본 금지했다. 주문장 깊이·최근 30체결·현재가처럼 bounded point/range 조회만 2초를 유지한다. 이 변경은 `UI_READ_MODEL`이며 거래 transaction SQL·commit·lock·hot-table index 수를 모두 0만큼 변경한다.
- 사용자 손익 요약과 관리자 전체 자금흐름의 체결 금액·수수료·세금·실현손익은 계좌의 전체 `stock_execution` 누적 `SUM` 대신 `stock_execution_account_day_summary(account_id, simulation_trade_date)`를 합산한다. 체결 after-commit은 기존 메모리 병합만 수행하고 30초 flush가 저빈도 요약 테이블을 bulk UPSERT하므로 주문·체결 transaction SQL·commit·lock은 증가하지 않는다. 포트폴리오 손익은 30초, 자산 이력은 60초, 현재 포지션은 10초로 폴링을 분리하고 background refetch를 금지했다.
- 일별 요약에는 매수/매도 gross·net, 수수료, 세금, 실현손익을 보존한다. REPORTS 단계의 정확 재구축은 함수형 `DATE(executed_at)` 조건이나 전체 이력 스캔이 아니라 기존 `idx_stock_execution_time_account`가 사용할 수 있는 `[T 00:00, T+1 00:00)` 한 거래일 범위만 계좌별로 집계한다. 관리자 수동 원장 조회는 자동 폴링하지 않고 read transaction을 10초로 제한해 분석 조회가 DB connection/socket 30초를 독점하지 않게 한다.

아직 완료되지 않은 운영 승인 범위는 MySQL 8 동시성·실데이터 규모 부하검증, 운영 DB ALTER와 재기동 후 live 통합검증입니다. 관리자 EOD API/UI는 핵심 cycle·대사·bounded 재시도, 원시 시각·cycle/최근 attempt 경과시간, 취소 뒤 반환 금액·수량과 기업행사/readiness 검사별 세부 표시까지 구현했습니다. 정상 phase를 정책 시각보다 앞당기는 수동 명령은 coordinator의 단일 실행 권한을 보존하기 위해 의도적으로 제공하지 않으며, 로그인 세션 브라우저 검증만 남았습니다. build/schema readiness 소스는 구현했지만 운영 DB에 신규 테이블·인덱스를 적용하기 전에는 live 완료로 판정하지 않습니다.

---

## 1. 리팩터링 전 확정한 핵심 문제

이 절은 2026-07-15 조사 시점에서 리팩터링 필요성을 확정한 기준선이다. 아래 항목 중 session fence, frozen snapshot, cycle 유일성, 오래된 거래일 복구, 수동 마감 검증은 현재 소스에 반영되었으며, 운영 DB ALTER·재기동·MySQL 동시성 부하검증 전이므로 운영 완료로는 판정하지 않는다.

### P0 — 배포 전 우선 수정

| 문제 | 현재 상태 | 영향 |
|---|---|---|
| 장마감 경계 | 체결·주문 최종 트랜잭션에서 세션을 재검증하지 않음 | 18시 이후 신규 승인 또는 장마감과의 경합 가능 |
| 정산 기준 | 현재 현금·보유·가격을 다시 조회 | 같은 날짜 재정산 결과가 달라질 수 있음 |
| 수동 전체 마감 | 리팩터링 전에는 요청·실행 양쪽 모두 거래시간 guard가 없었음. 현재 소스는 백엔드 요청을 AFTER_CLOSE로 제한하고 배치 실행기도 REGULAR를 재차 거부함 | 운영 DB·재기동 검증 전이며, 회귀 시 정규장 주문 일괄 취소·예약 반환 가능 |
| 전체 close 유일성 | 날짜별 논리적 cycle 제약 없음 | 실제 중복 full-close 날짜 존재 |
| 복구 날짜 | `currentDate.minusDays(1)`만 검사 | 오래된 미완료 거래일 누락 |
| 소스·실행 산출물 정합성 | 2026-07-15 진단 당시 보유지표가 `NULL`이었지만, 2026-07-16 최신 10개 정산일 1,020행은 세 보유지표가 모두 채워져 있다. 반면 실행 중 `out/production`에는 신규 EOD 클래스가 없고 EOD 핵심 테이블·귀속 컬럼도 미적용이다. | 보유지표 증상은 현재 재현되지 않지만 새 EOD 구현의 실행 산출물·스키마 정합성은 여전히 미검증 |

2026-07-15의 보유지표 `NULL` 원인을 stale IntelliJ class로 단정할 근거는 없었고, 2026-07-16 재조회에서는 최신 10개 정산일의 세 컬럼이 모두 non-null로 회복된 것을 확인했습니다. 그러나 실행 중 두 서버는 여전히 현재 워킹트리의 신규 EOD 클래스를 포함하지 않고 운영 EOD 스키마도 미적용이므로, 배포 전 build SHA·스키마 버전·실행 산출물 확인은 계속 의무화해야 합니다.

기존 `portfolio_snapshot`의 과거 `holding_quantity`·`reserved_sell_quantity`·`holding_position_count`가 모두 `NULL`인 row는 일괄 0 backfill 대상이 아닙니다. 구형 snapshot에는 당시 보유·예약·포지션 cohort를 정확히 복원할 불변 입력이 없으므로 0을 쓰면 “과거 기록 없음”을 “실제 0주”로 위조합니다. 세 값은 모두 NULL이거나 모두 유효해야 한다는 DDL CHECK, 집계 시 하나라도 누락되면 합계를 NULL로 반환하는 API, 프론트의 `-` 표시를 유지합니다. 신규 close cycle부터만 세 값을 필수로 저장하고 정산 완료 검증에 포함합니다. 따라서 이 항목은 미완료 backfill이 아니라 의도된 legacy data-quality 정책입니다.

### P1 — EOD 구조 안정화

- 장마감과 기업행사·자동 입금·시세 갱신 사이에 전역 phase barrier가 없음
- 기업행사 한 Job 안에서 이벤트별 부분 커밋 후 전체 Step은 FAILED가 될 수 있음
- 유상증자 마지막 청약일 장애 복구 공백
- 현재 활성 계좌 수로 과거 정산 완료 여부를 판단
- 자동시장 큐가 기업행사보다 먼저 복구될 수 있음
- 수동 신호가 생성 당시 거래일이 아니라 실행 시점의 `currentDate()`를 사용

### P2 — 부하와 운영 안정성

- 장마감 시 일별 집계와 스냅샷을 큰 트랜잭션 안에서 함께 처리
- 기업행사·시장 데이터 Job이 실제 대상이 없어도 주기적으로 JobRepository 이력을 생성
- signal 선두 지연 시 뒤 신호도 막히는 head-of-line blocking
- 인메모리 계좌 일일요약 누적값이 서버 종료 시 유실될 수 있음
- 설정 기본값이 코드·YAML·README에서 다름
- 자동 주문 생성의 폐기된 `fixed-delay-ms` 설정은 제거하고 실제 사용되는 `fixed-rate-ms=5000`만 `.env.example`·YAML·README·계약 테스트의 단일 계약으로 유지한다. 설정이 존재하지만 실행에는 반영되지 않는 상태를 허용하지 않는다.
- 대형 주문·체결 테이블에 `TIME(column)`, `DATE(column)` 기반 진단 쿼리가 사용되면 전체 스캔 발생

---

## 2. 실제 부하를 반영한 판단

2026-07-15 점검 구간의 최신 정상 처리 결과는 다음과 같습니다.

- 전체 장마감: 약 7.86초
- 포트폴리오 정산: 약 6.62초
- 최신 full close: 시뮬레이션 거래일 2026-10-27
- 취소 주문: 148건
- 보유 스냅샷: 216건
- 종목 스냅샷: 3건

최근 구현은 정상 상황에서 충분히 빨라졌습니다. 그러나 과거 꼬리 지연은 여전히 큽니다.

- 장마감 성공 최대: 약 53.9초
- 장마감 실패 실행: 주로 30~40초
- JDBC statement/socket timeout: 30초
- 현재 실행 세션에서도 자동시장 작업이 커밋을 30초 기다리다 연결 종료 오류 발생
- 관련 로그: `logs/stock-batch-service/2026-07-15/error.0.log`

DB 규모도 장마감 부하를 무시할 수준은 아닙니다.

| 테이블 | 대략적인 행 수 | 데이터/인덱스 특징 |
|---|---:|---|
| `stock_order` | 145만 | 인덱스 약 1.33GB |
| `stock_execution` | 88만 | 인덱스 약 588MB |
| `BATCH_JOB_EXECUTION_PARAMS` | 138만 | 고빈도 Job 파라미터 누적 |
| `BATCH_JOB_EXECUTION` | 24만 | 메타데이터 쓰기 지속 |
| `BATCH_STEP_EXECUTION` | 20만 | Step 실행 이력 누적 |
| 계좌 | 105 | 현재 정산 대상 규모는 작음 |
| 주문장 종목 | 3 | 종목 수는 현재 작음 |

2026-07-16 18:52 KST 운영 MySQL을 원장 스캔 없이 `information_schema`와 `SHOW CREATE TABLE`로 다시 읽은 기준선은 다음과 같습니다. `table_rows`는 InnoDB 추정치이며 정확한 `COUNT(*)`를 위해 대형 원장을 읽지 않았습니다.

| 테이블 | 추정 행 수 | 데이터 크기 | 인덱스 크기 | 인덱스 수 | 정의 SHA-256 |
|---|---:|---:|---:|---:|---|
| `stock_order` | 1,509,843 | 300,793,856B | 1,534,885,888B | 16 | `f837638e84861411892ed7da3d79fc55026485b07db531465637570d3e2b88fb` |
| `stock_execution` | 1,026,514 | 166,412,288B | 687,521,792B | 13 | `7aded9b98217214eb7324624f2a99261d1b0a746f83f1e8c03037de95ec181b5` |

11개 ALTER 후 두 정의 해시와 인덱스 수는 동일해야 합니다. 기준선 조회 순간 `Innodb_row_lock_current_waits=8`이었으나 즉시 재조회한 `performance_schema.data_lock_waits` edge는 0으로 해소되어 transient wait로 판정했습니다. 이 값은 ALTER 허용 근거가 아니라, 서비스가 실제로 살아 있는 동안 DDL을 실행하지 않아야 한다는 보조 증거입니다.

Performance Schema 누적값에는 다음이 확인됐습니다.

- `COMMIT`: 약 480만 회, 평균 약 260ms
- `stock_order` INSERT: 약 268만 회, 최대 약 37.5초
- 계좌 일일요약 UPSERT: 평균 약 474ms
- 일부 `TIME(created_at)` 기반 쿼리: 최대 약 200초

이 수치는 MySQL 기동 이후 누적값이므로 특정 EOD 한 번의 비용으로 해석하면 안 됩니다. 하지만 “스레드·연결 수를 늘리는 것”보다 쿼리 횟수, 메타데이터 쓰기, 유지보수 작업 중첩을 줄여야 한다는 근거로는 충분합니다.

---

## 3. 목표 불변식

리팩터링 후에는 다음 조건이 항상 성립해야 합니다.

1. 18시 이후 신규 주문·체결은 승인되지 않는다.
2. 18시 전에 승인된 in-flight 거래는 장마감이 기다렸다가 원장에 포함한다.
3. `LEDGER_FROZEN` 이후 해당 거래일 주문·체결·현금·보유 원장은 바뀌지 않는다.
4. 포트폴리오 정산은 언제 실행해도 같은 결과를 낸다.
5. 한 거래일·범위에는 논리적 close cycle이 정확히 하나만 존재한다.
6. 작업이 중간에 종료되어도 마지막 미완료 Step부터 재개한다.
7. 모든 필수 단계가 완료되지 않으면 다음 장을 열지 않는다.

“18시 이후 물리적 DB 커밋 0건” 자체는 최종 불변식으로 삼지 않는 것이 좋습니다. 17:59:59에 승인된 트랜잭션이 18:00:01에 물리적으로 커밋될 수는 있습니다. 중요한 것은 승인 시각과 원장 동결 순서입니다.

따라서 업무 효력 시각과 DB 기록 시각도 분리하는 것이 좋습니다.

- `business_effective_at`: 거래가 승인된 시뮬레이션 시각
- `recorded_at`: DB에 실제 기록된 시스템 시각

과거에 `created_at > updated_at`처럼 보이는 장마감 경계 데이터도 이 구분으로 방지할 수 있습니다.

---

## 4. 권장 시간 구조

현재 배율은 현실 2시간이 시뮬레이션 1일입니다.

- 18:00 → 다음 날 00:00: 현실 약 30분
- 00:00 → 06:00: 현실 약 30분

따라서 충분히 부하를 분산할 수 있습니다.

| 시뮬레이션 시간 | 단계 | 실행 내용 |
|---|---|---|
| T 18:00 즉시 | 거래 차단 | `OPEN → CLOSING`, epoch 증가, 신규 주문·체결 차단 |
| 18:00 직후 | 원장 동결 | in-flight drain, 미체결 상태 보존, 취소·예약 반환, 현금·보유·가격·대상 계좌 스냅샷 |
| 18:10 이후 | 포트폴리오 정산 | 불변 스냅샷만 사용 |
| T+1 00:00~04:30 | 야간 후처리 | 현금흐름, 기업행사 현금 단계, 차트·보고서 집계, 빈 보유·과거 데이터 정리 |
| 04:30~05:30 | 개장 준비 | 권리락·분할·상장 등 수량/가격 변환, 기준가격, regime, 프로필, Redis 큐 |
| 05:30~06:00 | readiness | 스냅샷 수량·기업행사·가격·큐·시장 상태 검증 |
| 06:00 | 개장 | `READY_TO_OPEN`인 경우에만 `OPEN` |

18:10은 하드코딩하지 않고 `settlement_eligible_at`으로 저장합니다.

```text
실행 가능 시점 =
max(LEDGER_FROZEN 완료 시각, 거래일 18:10)

구현에서는 `stock.batch.market-close.settlement-delay-simulation-minutes`(기본 10분)로 지연값을 설정한다. 주문량이 늘어 동결이 18:10 이후에 끝나면 `settlement_eligible_at`은 동결 완료 시각으로 밀린다. 이 대기는 Job 내부 `sleep`, 열린 DB 연결 또는 장기 트랜잭션으로 구현하지 않고 cycle의 실행 가능 시각만 영속화한다.
```

한 Job이 18시부터 00시까지 실행 상태로 대기하거나 `sleep`해서는 안 됩니다. DB coordinator가 단계 상태를 저장하고 각 시간대에 별도 Job을 실행해야 합니다.

---

## 5. 목표 상태 모델

```text
simulation_datetime ─────────────── 계속 진행
        │
        ├─ active_business_date ─── 실제 거래 원장의 기준일
        └─ preparing_business_date  다음 개장 준비 거래일

OPEN
  ↓
CLOSE_REQUESTED
  ├─ 내부 절차: ORDER_ENTRY_CLOSED
  └─ 내부 절차: EXECUTION_DRAINED
  ↓
LEDGER_FROZEN
  ↓ 18:10 이후
PORTFOLIO_SETTLED
  ↓ T+1 00:00 이후
OVERNIGHT_CASH_APPLIED
  ↓
CORPORATE_CASH_APPLIED
  ↓
REPORTS_AGGREGATED
  ↓ 04:30 이후
PREOPEN_SECURITY_TRANSFORMS_APPLIED
  ↓
MARKET_DATA_PREPARED
  ↓
AUTO_MARKET_PREPARED
  ↓
READY_TO_OPEN
  ↓ 06:00
OPEN
```

원시 시뮬레이션 시간은 `SimulationClockSnapshots.java`처럼 계속 흐르게 두되, 주문·체결·기업행사·정산은 `active_business_date`와 cycle을 기준으로 실행해야 합니다.

원시 시간이 장애 중 여러 날짜를 건너뛴 경우에는 다음 중 하나를 명시적으로 기록해야 합니다.

- 정상 거래일 cycle 생성
- 휴장일 cycle 생성
- 장애로 인한 `SKIPPED` cycle 생성

단순히 가장 오래된 미완료 row를 찾는 것만으로는 “cycle row 자체가 생성되지 않은 날짜”를 발견할 수 없습니다. 거래 캘린더 또는 누락 cycle 생성 규칙이 필요합니다.

현재 소스의 복구 정책은 캘린더가 아직 없는 시뮬레이션 특성을 고려해 다음처럼 명시했습니다.

- 원시 시각이 다음 거래일의 18:00을 이미 지났다면 그 날짜를 개장하지 않는다.
- 시장 설정이 모두 `CLOSED`이고 직전 cycle이 `COMPLETED`인 경우에만 하루를 전진한다.
- 한 coordinator poll에서 최대 한 날짜만 전진한다.
- 전진한 날짜에는 `cycle_kind=SKIPPED`, 사유, build/schema version을 가진 cycle을 남긴다. 다음 날짜도 이미 마감 시각을 지났으면 즉시 완료 상태로 유지한다.
- 종목 fence epoch는 하루마다 증가시키되 주문·체결 worker와 같은 executor를 사용하지 않는다.
- 원시 시각과 활성 거래일 차이가 해소되기 전까지 시장 OPEN을 차단한다.
- PRE_OPEN 복구 창을 놓쳐 원시 시각이 REGULAR로 넘어가도 실제 시장이 모두 `CLOSED`이면 가장 오래된 미정산 cycle 또는 cycle이 없는 오래된 활성 거래일의 freeze·정산 prefix를 계속 복구한다. 실제 시장이 하나라도 `OPEN`이면 launcher와 JobRepository 진입 전에 즉시 연기한다. 정상 REGULAR의 추가 비용은 `stock_post_close_cycle`·`stock_market_business_state` 제어행 인덱스 조회뿐이며 `stock_order`·`stock_execution`을 읽지 않는다.
- 원시 시각을 따라잡은 **마지막** `SKIPPED` cycle만 `REPORTS_AGGREGATED`로 재가동하여 다음 실제 개장일의 PREOPEN 기업행사·시장 데이터·regime·프로필 큐·readiness suffix를 수행한다. 그렇지 않으면 완료된 `SKIPPED` cycle이 개장 guard를 통과시켜 준비되지 않은 다음 장을 열 수 있다.

이 방식은 장애 기간만큼 빈 장마감·정산·보고서 Job을 실행하지 않으면서도 누락 날짜를 감사 가능하게 보존하고, 실제로 열릴 날짜의 준비 단계만 한 번 실행합니다. 재가동 판정과 상태 변경은 `stock_market_business_state`·`stock_post_close_cycle` 제어행만 사용하며 `stock_order`·`stock_execution`을 읽지 않습니다. 향후 주말·휴장일 캘린더가 도입되면 `SKIPPED` 사유를 `HOLIDAY`, `OUTAGE`, `ADMIN_SKIP`으로 확장합니다.

---

## 6. DB 모델 계획

### 6.1 거래일·세션

`stock_market_business_state`

- `id`
- `active_business_date`
- `preparing_business_date`
- `raw_simulation_date`
- `version`
- `updated_at`

`stock_market_session_fence`

- `symbol`
- `business_date`
- `session_epoch`
- `state`: `OPEN`, `CLOSING`, `CLOSED`, `PREPARING`
- `state_changed_at`
- `version`

전역 한 행이 아니라 종목별 fence를 사용해야 주문량 증가 시 전역 병목을 피할 수 있습니다.

### 6.2 논리적 cycle과 실행 시도 분리

`stock_post_close_cycle`

- `id`
- `business_date`
- `scope_type`
- `scope_key` — 전체 시장이면 `ALL`, 절대 `NULL` 금지
- `phase`
- `status`
- `version`
- `owner_id`
- `lease_until`
- `next_retry_at` — 실패·연기된 phase의 서버 시각 기준 다음 claim 가능 시각
- `close_run_id`
- `settlement_eligible_at`
- `attempt_count`
- `started_at`, `completed_at`
- `last_error_code`, `last_error_message`
- `build_version`, `schema_version`

유일성:

```sql
UNIQUE (business_date, scope_type, scope_key)
```

MySQL의 UNIQUE 인덱스는 `NULL` 값을 여러 개 허용하므로, 현재처럼 `symbol=NULL`로 전체 실행을 표현하면서 단순 UNIQUE를 추가하면 중복 full close를 막을 수 없습니다. `scope_key='ALL'` 같은 NOT NULL 키가 필요합니다.

`stock_post_close_phase_attempt`

- `cycle_id`
- `phase`
- `attempt_no`
- `batch_job_execution_id`
- `owner_id`
- `status`
- `started_at`, `completed_at`
- `error_code`, `error_message`
- `build_version`, `schema_version`

논리적 cycle은 하나지만 실패·재시도 이력은 여러 건 남길 수 있어야 합니다.

cycle/attempt 조회와 lease 갱신은 coordinator 제어면(control plane)에서만 수행하며, 주문 생성·정정·취소·체결 트랜잭션에는 넣지 않습니다. 주문·체결 hot path가 참조하는 EOD 데이터는 종목 PK로 정확히 찾는 `stock_market_session_fence` 한 행뿐이어야 합니다.

`stock_post_close_cycle`과 `stock_post_close_phase_attempt`는 거래량에 비례해 증가하거나 조회되는 테이블이 아닙니다. 논리적 cycle은 거래일·범위당 1행, attempt는 phase 재시도당 1행만 생성합니다.

- 논리 cycle 재사용: `(business_date, scope_type, scope_key)` UNIQUE exact lookup
- 가장 오래된 복구 대상: `(phase, status, business_date, id)` 인덱스 범위
- 만료 lease 회수: `(status, lease_until, business_date, id)` 인덱스 범위
- phase 선점: cycle PK 조건부 UPDATE 1회

정규장 거래량이 증가해도 이 제어면 쿼리 횟수는 늘지 않아야 합니다. EOD coordinator와 주문·체결 executor는 별도 실행 자원 예산을 적용하고, cycle polling은 대상 phase가 없으면 DB write와 Spring Batch JobExecution을 만들지 않습니다.

정규장 coordinator poll은 cycle ID를 먼저 찾고 본문을 다시 읽는 2회 조회를 사용하지 않습니다. `(scope_type, scope_key, business_date, status, id)` 제어 인덱스에서 필요한 cycle 본문을 한 SQL로 읽고 명시적 read transaction/commit 없이 반환합니다. 따라서 10초당 제어 테이블 조회 1회로 고정되며 거래량과 무관합니다.

phase lease는 만료시각만 최초 기록하고 방치하지 않는다. 기업행사·보고서 대상이 늘어 Job이 기본 lease 180초를 넘더라도 다른 노드가 같은 phase를 재선점하지 않도록, 기존 Job lock heartbeat가 `cycle_id`가 있는 EOD Job의 cycle lease도 기본 30초마다 갱신한다. 갱신은 `stock_post_close_cycle` PK 한 행만 UPDATE하며 주문·체결 원장을 조회하지 않는다. Job lock 또는 cycle 소유권 중 하나라도 잃으면 성공으로 phase를 넘기지 않고 실패 처리하며, heartbeat 주기는 Job lock TTL과 cycle lease보다 모두 짧아야 한다.

### 6.3 불변 스냅샷

`stock_close_account_snapshot`

- `cycle_id`, `close_run_id`, `account_id`
- 계좌 활성 상태와 정산 대상 여부
- 장마감 전 가용 현금
- 장마감 전 매수 예약금
- 유상증자 청약 예약금
- 미체결 취소 후 현금
- 외부 순입금 watermark
- 장마감 평가가격 기준 보유 평가액
- 총 보유수량, 예약 매도수량, 보유 포지션 수
- 입력 버전과 생성 시각

`stock_close_price_snapshot`

- `cycle_id`, `symbol`
- 종가와 가격 출처
- 전일종가
- OHLC
- 마지막 체결 ID 또는 watermark
- 주문장/비주문장 구분

기존 `stock_holding_snapshot`의 수량 의미는 다음처럼 고정합니다.

- `quantity`: 예약 매도분을 포함한 총 소유수량
- `reserved_quantity`: `quantity` 안에 포함된 처분 제한 수량
- 가용 보유수량: 별도 원장이 아니라 `quantity - reserved_quantity`로 계산
- `average_price`: 총 소유수량의 평균단가
- `evaluation_price`: 해당 close cycle의 평가가격

이 정의는 `StockHolding.getAvailableQuantity()`, 매도 예약 시 `reserved_quantity`만 증가시키는 로직, 체결 시 `quantity`와 `reserved_quantity`를 함께 감소시키는 로직과 일치합니다. 따라서 포트폴리오 평가액은 `quantity × evaluation_price`이며, 여기에 `reserved_quantity × evaluation_price`를 다시 더하면 예약 매도분을 이중 평가하게 됩니다.

`stock_close_open_order_summary`

- 취소 전 미체결 매수·매도 주문 수
- 취소 전 잔량
- 취소 전 예약 현금·예약 수량
- 취소 후 잔존 건수
- 취소·반환 검증 결과

포트폴리오 정산 입력은 장마감 직전 기준을 사용합니다.

```text
총자산
= 장마감 전 가용 현금
+ 장마감 전 매수 예약금
+ 청약 예약금
+ 총 소유수량(quantity) × 장마감 가격
```

여기서 `총 소유수량 = 가용 보유수량 + 예약 매도수량`입니다. 미체결 취소 후 현금과도 총액이 일치하는지 별도로 검증해야 합니다. 장마감 전 예약금과 취소 후 현금을 동시에 더하거나, 총 소유수량에 예약 매도수량을 다시 더하면 이중 계산이 됩니다.

`stock_portfolio_snapshot`에는 다음을 추가합니다.

- `close_cycle_id`
- `close_run_id`
- `input_hash`
- `calculation_version`
- `data_quality_status`
- `source_build_version`

신규 데이터의 유일성은 `(close_cycle_id, account_id)`가 되어야 합니다. 현재 `(account_id, snapshot_date)`는 조회 보조 키로 유지할 수 있습니다.

### 6.4 기업행사 처리 원장

`stock_corporate_action_processing`

```text
(action_id, account_id 또는 scope_key, action_phase, effective_business_date)
```

- `status`
- `attempt_count`
- `processed_at`
- `amount`, `quantity`
- `ledger_reference_id`
- `last_error`

이를 통해 T일 마지막 자동청약을 T+1일에 복구하면서도 납입·상장 완료 후 뒤늦은 청약이 실행되는 것을 막습니다.

### 6.5 Signal

`stock_batch_job_signal`에 추가:

- `requested_business_date`
- `requested_session_epoch`
- `expected_cycle_id`
- `eligible_at`
- `next_attempt_at`
- `attempt_count`
- `max_attempts`
- `claim_token`
- `lease_until`
- `failure_class`
- `completed_count`

수동 신호는 생성 당시 실행 기준을 고정해야 합니다. 전체시장 마감·수동 현금 신호는 `requested_business_date`를, 종목 마감·미체결 취소 신호는 여기에 `requested_session_epoch`까지 `StockBatchJobLauncher.java`에 전달합니다. 요청 시점에 대상 cycle이 이미 있으면 `expected_cycle_id`도 저장·검증하고, 아직 없으면 nullable로 두되 `(business_date, scope_type, scope_key)` 유일 제약으로 첫 실행이 만든 동일 논리 cycle을 모든 재시도가 재사용합니다. 전체시장에는 단일 종목 epoch가 없으므로 nullable epoch를 억지로 채우지 않습니다. 전체시장 마감과 수동 현금은 REGULAR에서 cycle 생성·JobRepository 기록 전에 거부합니다. 종목 마감·미체결 취소는 관리자가 먼저 해당 종목 fence를 닫은 경우에만 REGULAR에서도 실행할 수 있으며, 열린 fence는 signal 검증과 업무 서비스가 이중으로 거부합니다. 지연 종목 신호의 PRE_OPEN 복구는 생성 당시 거래일·epoch와, 존재했다면 cycle까지 일치하는 경우에만 허용합니다.

---

## 7. Job과 Step 구조

[Spring Batch 6 Job 실행 문서](https://docs.spring.io/spring-batch/reference/job/running.html)의 파라미터 계약대로 `JobInstance`는 Job 이름과 identifying parameter 조합으로 식별됩니다. `signalId`나 임의 `runVersion`을 identifying parameter로 넣으면 동일 거래일 작업이 별도 JobInstance로 만들어질 수 있으므로 현재 구현에서는 둘 다 식별 키로 사용하지 않습니다.

권장 파라미터는 다음과 같습니다.

- identifying: `cycleId`, `businessDate`, `scopeKey`, `phaseRevision`
- non-identifying: `signalId`, `requestedBy`, `triggerSource`, `attemptToken`

실패 재시도는 같은 JobInstance를 재시작해야 합니다. 정상 완료 후 강제 재처리가 필요하면 무작위 `runVersion`이 아니라 명시적 correction workflow와 `phaseRevision`을 사용합니다.

일반적인 `FAILED`, 정책상 `DEFERRED`, bounded cohort의 `BOUNDED_PROGRESS`는 `phaseRevision`을 변경하지 않는다. 이 상태 전이는 attempt·lease·`next_retry_at`만 갱신하므로 동일 identifying parameter의 JobInstance를 재시작하고 완료된 Step을 재사용한다. `phaseRevision` 증가는 거래 없는 `SKIPPED` cycle을 다음 개장 준비 suffix로 명시적으로 재활성화하거나 승인된 correction workflow를 시작할 때만 허용한다. 이 계약은 서비스 테스트에서 실패·연기·bounded continuation 이후 revision이 그대로인지 검증한다. 불필요한 새 JobInstance와 Step 재실행을 막아 야간 원장 재조회와 JobRepository 쓰기가 주문·체결 부하로 전파되지 않게 한다.

[Spring Batch 6 Step restart 문서](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing/restart.html)처럼 완료된 Step은 재시작 시 기본적으로 건너뛰므로, 단계 경계를 올바르게 잡으면 장애 직전까지 완료된 작업을 재사용할 수 있습니다. 재실행이 필요한 검증 Step만 `allowStartIfComplete`를 제한적으로 사용해야 합니다.

### 7.1 `market-close-freeze` Job

`MarketCloseRolloverJob.java`는 `market-close-snapshot-step` 하나를 유지하되, 업무 트랜잭션은 하나로 유지하지 않는다. Spring Batch Step은 논리적 phase와 JobRepository 재시작 단위이고, 실제 대량 원장은 cycle snapshot의 PK·`released_at`을 durable checkpoint로 사용하는 bounded 업무 트랜잭션으로 나눈다. 주문량이 늘어도 Step 하나가 DB connection·계좌·보유·주문 잠금을 전체 실행시간 동안 보유하지 않는다. 정상 실행에서 cycle은 `CLOSE_REQUESTED`에 머문 채 주문 차단과 in-flight drain을 수행한 뒤 검증이 끝나면 `LEDGER_FROZEN`으로 전이한다. `ORDER_ENTRY_CLOSED`와 `EXECUTION_DRAINED`는 기존 데이터·복구 호환을 위한 enum/DDL 상태이며 정상 타임라인의 별도 영속 checkpoint로 표시하지 않는다.

이 단일 Step은 Step 설계를 생략한 것이 아니라 18시 임계 경로의 의도적인 예외다. 원격 JobRepository Step 하나는 시작·상태 갱신·종료에 추가 metadata commit을 만들기 때문에, 아래 8개 업무 checkpoint를 각각 Spring Batch Step으로 바꾸면 열린 주문 수와 무관한 고정 commit 지연이 장마감에 누적된다. 재시작 정확성은 이미 `stock_post_close_cycle`, `stock_market_close_run`, frozen cohort PK, `released_at`, 계좌별 대사 상태가 제공한다. 따라서 실제 MySQL A/B에서 freeze p95가 악화되지 않는다는 근거 없이 close-critical Step 수를 늘리지 않는다. 반대로 00시 이후 기업행사는 거래와 겹치지 않으므로 의미 있는 action stage마다 Step을 두어 재시작 위치와 운영 가시성을 얻는다.

하나의 Step 안에서 다음 업무 순서를 지킨다.

1. `claim-close-cycle`
   - cycle lease 선점
   - 요청 거래일·epoch 검증

2. `close-order-entry`
   - 모든 대상 종목 fence를 symbol 오름차순 잠금
   - `OPEN → CLOSING`
   - epoch 증가

3. `drain-in-flight-execution`
   - 기존 공유 fence 트랜잭션 종료 대기
   - 종목별 fence의 배타 잠금 획득과 `CLOSING` 커밋 자체를 drain barrier로 사용
   - 별도 전역 active-counter 조회나 `stock_order`·`stock_execution` 재스캔은 추가하지 않음

4. `capture-pre-cancel-order`
   - 취소 전 주문 수·잔량·예약금 스냅샷
   - 기존 `idx_stock_order_market_status_symbol`에서 열린 주문 symbol만 조회
   - 고정된 `status + symbol`별 주문 PK 오름차순 1,000건씩 캡처
   - snapshot의 `(close_cycle_id, symbol, source_order_status, order_id)` 인덱스와 `max(order_id)`를 스트림별 재시작 checkpoint로 사용
   - 과거 `FILLED/CANCELLED/REJECTED` 주문을 매 거래일 `order_id=0`부터 다시 스캔하지 않음

5. `freeze-ledger-snapshot`
   - 보유는 계좌 500개씩 keyset snapshot
   - 계좌 현금·예약금·외부 순입금도 계좌 500개씩 snapshot
   - 첫 계좌 chunk의 cash-flow watermark를 이후 chunk와 재시작에서도 고정
   - 가격은 이미 고정한 보유 snapshot과 현재 종가만 사용

6. `cancel-and-release`
   - 미체결 취소
   - 매수 예약금·매도 예약수량 반환
   - 500주문마다 취소·반환·`released_at`을 같은 트랜잭션에 커밋
   - 같은 cycle 재시도에도 `released_at is null` cohort만 처리하여 중복 반환 방지
   - 최종 완료 검증도 `released_at is null` snapshot 인덱스만 확인하며, 모든 캡처 주문을 `stock_order`와 다시 조인하지 않음
   - frozen cohort의 계좌 ID 오름차순 → 매도 보유 `(account_id, symbol)` 오름차순 → 캡처 주문 순서로 잠금
   - 관리자 현금 조정·계좌 철회가 동시에 진입해도 `주문 → 계좌` 역순환을 만들지 않음

7. `validate-market-freeze`
   - 취소 건수와 반환 원장 대사
   - 계좌 현금 대사는 500계좌씩 커밋
   - 스냅샷 대상 누락 검사
   - 동결 이후 변동 여부 검사

8. `complete-market-freeze`
   - `LEDGER_FROZEN`
   - 모든 fence `CLOSED`

cycle claim·물리적 close run 연결·fence `CLOSING` 전환은 첫 원장 chunk 전에 영속화한다. 서버가 종료되면 lease 만료 후 같은 cycle/JobInstance를 재시작한다. 주문 캡처는 snapshot의 `max(order_id)`, 보유·계좌 snapshot은 `max(account_id)`, 주문 반환은 `released_at`을 checkpoint로 사용한다. 각 주문 chunk에서 주문 취소와 예약금·예약수량 반환이 함께 롤백되므로 “취소만 성공”하는 부분 상태가 없고, 이미 커밋한 chunk는 재실행하지 않는다.

현재 소스는 임의 `runVersion` 상수와 `settlePortfoliosForce` 우회 메서드를 제거했다. 완료된 동일 cycle 정산은 같은 JobInstance의 `ALREADY_COMPLETE`로 끝나며, 실제 재계산은 문서화된 correction workflow 없이 새 JobInstance를 만들 수 없다. `signalId`와 요청 시각도 계속 non-identifying으로 유지해 신호 재시도가 동일 JobInstance를 재사용한다.

종목 신호는 close 첫 시도가 fence를 정확히 한 번 `OPEN → CLOSING/CLOSED`로 전환한 뒤 Redis/DB 일시 오류로 실패할 수 있다. 이 경우에만 동일 거래일·동일 cycle·현재 epoch=`requested_epoch + 1`·상태 `CLOSING/CLOSED`를 복구 가능한 신호로 인정한다. 초기 `CLOSE_REQUESTED/PENDING` cycle에서 이미 epoch가 다른 경우나 두 번 이상 epoch가 진행된 신호는 영구 stale로 거부한다. 이 검증은 작은 cycle/fence 테이블만 읽고 주문·체결 hot table을 조회하지 않는다.

Spring Batch Step을 주문별로 늘리지는 않는다. 1,000/500 기본 chunk는 현재 거래량에서도 항상 적용하여 데이터 증가 뒤 급하게 경로를 바꾸는 일을 피한다. 실제 MySQL 부하검증에서 undo/redo·commit 왕복·freeze p95를 비교한 뒤에만 환경값을 조정하며, 한 번에 10,000주문 또는 2,000계좌를 넘기는 설정은 코드 계약 테스트와 별도 운영 승인을 통과해야 한다.

### 7.2 `portfolio-settlement` Job

현재 `AccountSettlementTargetReader.java`의 실시간 조회를 제거합니다.

1. `validate-close-snapshot-step`
2. `portfolio-settlement-step`
   - paging 입력은 계좌별 합계가 이미 고정된 `stock_close_account_snapshot`만 읽음
   - Step 시작 검증에서 `stock_holding_snapshot`을 cycle당 한 번 집계해 계좌 합계와 대사
   - 각 페이지마다 전체 보유 snapshot을 다시 `GROUP BY`하지 않음
3. `complete-portfolio-settlement-step`
   - frozen cohort 대상 수, 저장 결과 수, 입력 hash, 현금·평가액·총자산·수익률·보유지표를 다시 대사
   - 대사가 모두 통과한 같은 업무 트랜잭션에서 cycle을 `PORTFOLIO_SETTLED`로 전이

별도 `reconcile-portfolio-snapshot-step`은 두지 않는다. 현재 완료 Step의 대사와 phase 전이는 하나의 권위 있는 업무 트랜잭션이어야 하며, 둘을 나누면 재시작 이득 없이 대사 완료와 phase 전이 사이의 비권위 중간 상태 및 JobRepository commit만 하나 늘어난다. 실제 MySQL A/B에서 분리의 이점이 증명되기 전에는 현재 3-Step 구조를 유지한다.

Writer는 계좌마다 UPDATE 후 INSERT하는 두 번의 호출이나 드라이버 설정에 따라 계좌 수만큼 전송될 수 있는 `batchUpdate` 대신, 최대 500행의 명시적 `INSERT ... AS incoming ON DUPLICATE KEY UPDATE`로 변경합니다. 기본 정산 chunk 200에서는 한 문장이고, 500행을 넘는 설정은 동일 Spring Batch 업무 트랜잭션 안에서 500행씩 나눕니다.

정산 Step은 200계좌 단위로 커밋하므로 마지막 검증 Step 전에는 일부 `portfolio_snapshot`이 물리적으로 존재할 수 있습니다. 이 행은 실패 복구 입력일 뿐 사용자 확정 데이터가 아닙니다. 따라서 모든 계좌 이력·수익률 순위·관리자 총자산 조회는 다음 공개 규칙을 적용합니다.

- 신규 snapshot: 연결된 full-market cycle이 `PORTFOLIO_SETTLED` 이상일 때만 공개
- migration 전 legacy snapshot: `close_cycle_id IS NULL AND close_run_id IS NULL`인 경우에만 공개
- `LEDGER_FROZEN` 또는 정산 실행 중인 cycle: 행 수와 무관하게 전부 비공개
- 공개 여부 판단은 cycle PK 조인만 추가하며 `stock_order`·`stock_execution` 조회나 잠금은 금지

수동 clock 판정도 동일한 cycle/metric과 `stock_market_business_state` singleton을 사용합니다. 현재 활성 계좌 수나 정산 snapshot 수를 매 polling마다 다시 세지 않으며 `stock_order`·`stock_execution`도 읽지 않습니다. 원시 시뮬레이션 날짜의 `today` 또는 `minusDays(1)`을 거래일로 추정하지 않고 `active_business_date`를 권위 기준으로 삼습니다. API가 반환하는 `availableJumpActions`만 프론트 버튼을 활성화해 화면과 서버의 단계 조건을 중복 구현하지 않습니다.

- `TODAY_MARKET_CLOSE`: 정규장이고 `raw_simulation_date = active_business_date`, `preparing_business_date IS NULL`일 때만 18:00으로 이동합니다. 이 명령은 EOD Job을 직접 실행하지 않으며 이후 시장 차단·원장 동결·정산은 coordinator가 수행합니다.
- `NEXT_SIMULATION_DAY_START`: AFTER_CLOSE이고 원시 일자와 활성 거래일이 일치하며, 동결 cohort의 `settlement_missing_account_count=0`과 대상/완료 수가 일치하는 `PORTFOLIO_SETTLED` 이상일 때만 T+1 00:00으로 이동합니다.
- `NEXT_MARKET_OPEN`: `READY_TO_OPEN` 이상이고 PRE_OPEN의 원시 일자가 `preparing_business_date = active_business_date + 1일`과 정확히 일치할 때만 06:00으로 이동합니다. 원시 시간이 여러 날짜 앞선 경우 중간의 SKIPPED cycle이 완료됐더라도 바로 개장하지 않습니다.

관리자 화면의 세 동작은 각각 `오늘 18:00 진입`, `다음 일자 00:00 진입`, `다음 장 06:00 진입`으로 표현합니다. 버튼은 작업 실행기가 아니라 시간대 진입 게이트이며, 활성 거래일·다음 준비 거래일과 비활성 사유를 함께 표시합니다. 1초 polling 비용은 clock 1행과 business-state/cycle/metric 제어행 한 번씩의 bounded 조회로 제한합니다.

완료 판정은 현재 활성 계좌 수가 아니라 동결된 계좌 cohort를 기준으로 합니다.

```sql
NOT EXISTS (
  SELECT 1
    FROM stock_close_account_snapshot a
   WHERE a.close_cycle_id = ?
     AND a.settlement_target = true
     AND NOT EXISTS (
       SELECT 1
         FROM stock_portfolio_snapshot p
        WHERE p.close_cycle_id = a.close_cycle_id
          AND p.account_id = a.account_id
     )
)
```

### 7.3 `overnight-cash-and-actions` Job

00시 이후 coordinator는 자동 참여자 정기 자금 Job을 먼저 독립 실행해 `OVERNIGHT_CASH_APPLIED`를 확정하고, 그 다음 `CorporateActionJob`의 `CASH` flow를 실행한다. 현금 기업행사 flow는 다음 Spring Batch Step으로 분리한다.

1. `cash-dividend-payment-step`
2. `capital-increase-auto-subscription-step`
3. `capital-increase-payment-step`
   - 미청약 권리 만료를 bounded entitlement chunk로 함께 처리
4. `validate-corporate-cash-step`
   - due action과 처리 원장을 대사하고 미완료가 있으면 Job 실패

각 action/account chunk는 기존 `REQUIRES_NEW`, 기본 200계좌, 최대 1,000계좌와 처리 원장을 유지한다. 한 action unit이 실패해도 같은 Step의 다른 action은 끝까지 시도하지만, Step 종료 시 집계 예외를 발생시켜 의존하는 다음 Step으로는 진행하지 않는다. 예를 들어 자동청약 일부 실패를 무시한 채 납입 상태를 `PAID`로 바꿔 최종 검증에서 누락시키는 상태 전이를 금지한다. 각 action stage는 한 실행에서 due action을 기본 25건까지만 읽으므로 `allowStartIfComplete(true)`로 재진입 가능하게 둔다. 그렇지 않으면 첫 25건을 성공한 Step이 완료 상태로 건너뛰어 26번째 이후 action이 영구 대기한다. 재시작 시 stage 자체는 다시 들어가되 processing ledger의 완료행과 이미 전이된 action 상태가 기존 성공분을 건너뛰며, 마지막 검증 Step은 남은 due work를 다시 fail-closed 처리한다.

이때 마지막 검증 실패를 일반 장애와 동일하게 누적 지수 백오프하면 25건 청크가 정상적으로 전진하는 대량 backlog도 30초→60초→120초→240초로 늦어져 PRE_OPEN 마감시간을 놓칠 수 있다. 따라서 `StockBatchJobRunner`는 FAILED JobExecution에서도 현재 시도의 Step write count 합계를 응답에 보존한다. 단, 합계가 양수라는 사실만으로 모든 Job을 빠르게 재개하지 않는다. 최종 validation이 bounded cohort 잔여를 의도적으로 알리는 기업행사 CASH/PREOPEN phase에서만 cycle attempt를 `BOUNDED_PROGRESS`로 종료하고 `retry-base-seconds`(기본 30초) 뒤 같은 JobInstance를 재개하며, 이 attempt는 무진전 실패 지수에 포함하지 않는다. 보고서·정산·시세 등 다른 phase의 부분 쓰기 실패와 기업행사의 합계 0 실패는 같은 phase의 FAILED 이력을 기준으로 최대 900초까지 지수 백오프한다. 모든 판정은 Spring Batch metadata와 EOD 제어행만 사용하고 `stock_order`·`stock_execution`을 추가 조회하지 않는다. 이 재진입은 00시 이후 단일 heavy-admission에서만 발생하며 `next_retry_at` 이전에는 JobRepository 실행 이력도 만들지 않는다.

같은 날 배당금을 자동청약 자금으로 사용할 정책이라면 현금배당 → 자동청약 순서를 명시적으로 유지합니다.

야간 Job의 물리적 실행 시각이 T+1이어도 기업행사 현금 flow의 `businessDate`는 close cycle의 T로 고정합니다. 따라서 T일이 청약 마지막 날인 작업은 `subscription_end_date >= T` 조건으로 복구되고, T+1 실행 시각을 조회 기준으로 사용해 영구 누락되지 않습니다. 반대로 PREOPEN 수량·가격 변환은 `businessDate=T+1`, `requiredCloseDate=T`를 별도 identifying parameter로 전달합니다. 두 날짜를 하나의 `today`로 합치거나 재시작 시 현재 시각으로 다시 계산하지 않습니다.

### 7.4 `preopen-security-transform` Job

1. `apply-ex-rights-step` — 권리락·배당락 가격 조정과 entitlement 생성
2. `capital-increase-listing-step` — 유상증자 신주 반영
3. `free-share-listing-step` — 무상증자·주식배당 반영
4. `stock-split-step` — 액면분할
5. `delisting-step` — 상장폐지와 잔존 주문 bounded 취소
6. `validate-preopen-security-transform-step` — 수량·발행주식·가격·processing ledger 후조건

현재 소스는 기본 coordinator의 `CASH`와 `PREOPEN_SECURITY_TRANSFORMS` operation을 위 flow로 분기한다. bounded due-action cohort가 남아 validation에서 실패한 JobInstance를 재개할 때는 모든 stage Step이 `allowStartIfComplete(true)`로 다시 진입하고, 이미 완료된 action/account는 processing ledger와 상태 조건으로 건너뛴다. 실제 예외로 중단된 Step 역시 같은 원장 checkpoint에서 미완료 action만 재개한다. coordinator를 명시적으로 끈 호환 모드의 `ALL` operation만 기존 `apply-due-corporate-actions-step`을 유지하며 기본 EOD 경로에서는 실행하지 않는다.

### 7.5 `preopen-market-preparation` Job

1. 비주문장 종목 기준가격 준비
2. 일일 regime 생성
3. 프로필 주문 스케줄 계산
4. Redis ready-symbol 큐 재구축
5. 주문장·현재가 Redis 검증

비주문장 기준가격 준비는 같은 lightweight task라도 실행 문맥을 구분한다. 일반 주기 실행은 한 provider 또는 Redis 장애가 다른 종목까지 막지 않게 부분 성공을 허용하고, `closeCycleId`를 가진 PRE_OPEN 실행은 한 종목이라도 provider·검증·DB 갱신·Redis publish에 실패하면 `MARKET_DATA_PREPARED` phase를 전진시키지 않는다. 성공 종목은 종목별 짧은 트랜잭션으로 유지하므로 실패 종목 때문에 전체 대상의 DB 잠금을 함께 롤백하거나 오래 보유하지 않는다. 같은 provider quote를 재시도할 때는 동일 tick을 중복 저장하지 않는다.

### 7.6 `market-open-readiness` Job

- 전일 frozen 정산 cohort와 대사가 완료됐는지
- 활성/준비 거래일이 cycle과 일치하는지
- 모든 활성 시장이 `CLOSED`인지
- 모든 활성 종목 fence가 다음 거래일의 `PREPARING`인지
- 종가·주문장 일별 snapshot이 완전한지
- 기업행사 현금 단계가 완료됐는지
- PRE_OPEN 기업행사 수량·가격 변환이 완료됐는지
- 자동시장 일일 regime이 준비됐는지
- Redis profile queue가 bounded DB schedule projection과 정확히 같은지
- cycle의 build/schema version이 현재 실행 산출물과 일치하는지

모두 통과한 경우만 `READY_TO_OPEN → OPEN`으로 이동합니다.

현재 `PostCloseReadinessService`는 위 10개 검사를 `AUTO_MARKET_PREPARED` 이후 PRE_OPEN에 한 번 실행하고, 검사별 결과를 `stock_post_close_readiness_check`의 `(close_cycle_id, check_code)` PK에 정확히 10행 저장합니다. 실패 Step의 바깥 resourceless transaction이 종료돼도 진단이 남도록 별도 `REQUIRES_NEW`에서 교체하고, 실패 count와 500자 이내 메시지를 보존합니다. 다음 정상 재시도는 같은 cycle의 10행만 교체하므로 시도별 무제한 상세 이력이 늘어나지 않습니다. attempt 이력은 기존 `stock_post_close_phase_attempt`가 담당합니다.

시장·fence·snapshot·regime·정산 검사는 config/control/snapshot 테이블의 단일 bounded 조회, 기업행사는 processing ledger 미완료 count, Redis는 profile enum으로 상한이 고정된 zset snapshot, 실행 정체성은 cycle PK 조회만 사용합니다. `stock_order`·`stock_execution`·`stock_holding`을 직접 조회하거나 변경하지 않는 정적 계약 테스트를 두며, 관리자 API도 readiness 원장을 재계산하지 않고 cycle PK와 display order로 저장 결과만 읽습니다. 따라서 이 상세 진단 때문에 정규장 주문·체결 SQL·잠금·commit·hot-ledger 인덱스가 증가하지 않습니다.

### 7.7 `post-close-report-aggregation` Job

보고서 Job은 symbol 보고서, 계좌 보고서, 계좌 일일요약 재구축을 별도 Step으로 유지합니다.

1. `aggregate-order-book-daily-report-step`
2. `aggregate-account-daily-report-step`
3. `rebuild-execution-account-day-summary-step`

세 Step의 outer tasklet transaction manager는 resourceless입니다. 실제 업무 SQL은 종목별 또는 재구축 단위의 짧은 business `REQUIRES_NEW`에서만 실행합니다. 따라서 한 Step이 여러 종목을 순회하는 동안 business connection 하나를 유휴 상태로 계속 점유하지 않습니다.

각 종목 집계는 `(close_run_id, symbol)` 결과만 짧은 `REQUIRES_NEW` 안에서 삭제·재생성합니다. 한 tasklet 반복은 `(close_cycle_id, symbol)` 키셋으로 기본 25개, 최대 200개의 cohort를 처리한 뒤 마지막 symbol을 Spring Batch `ExecutionContext`에 한 번 커밋합니다. Step 중간 실패 후 재시작하면 이미 체크포인트된 앞 cohort는 건너뛰고, 체크포인트 직전 장애에서는 현재 cohort만 다시 실행합니다. 재실행되는 각 symbol은 같은 짧은 트랜잭션에서 자기 결과만 삭제·재생성하므로 결과는 멱등이며, 복구 원본 체결 재조회는 최대 설정 cohort로 제한됩니다. 종목마다 metadata COMMIT을 만들지 않으면서도 전체 거래일을 첫 symbol부터 다시 읽는 복구는 금지하고, cycle이 `REPORTS_AGGREGATED`에 도달하기 전의 부분 결과는 최종 보고서로 승인하지 않습니다.

계좌별 참가자 유형도 보고서 실행 시점의 `stock_auto_participant`·`stock_listing_auto_account_config`를 다시 읽지 않습니다. 장마감 계좌 스냅샷 생성 시 `MANUAL_PARTICIPANT`, `AUTO_PARTICIPANT`, `LISTING_UNDERWRITER`를 `stock_close_account_snapshot.participant_category`에 함께 동결하고, 야간 `stock_execution` 집계는 `(close_cycle_id, account_id)` 유일키로 이 작은 스냅샷만 조인합니다. 따라서 장마감 후 프로필 철회·변경에도 같은 cycle의 수급 분류가 재현되며, 기존의 변경 가능한 설정 조인이 제거됩니다. 과거 snapshot backfill은 당시 설정이 보존되지 않았으므로 frozen `user_key`와 현재 저빈도 참가자 registry를 사용하는 최선 추정치이고, 신규 cycle부터만 정확한 시점 분류를 보장합니다. 이 보정과 보고서 조인은 정규장 주문 INSERT·체결 INSERT 경로에는 들어가지 않습니다.

보고서·차트·관리자 일별 수급 조회도 `close_run.status='COMPLETED'`만으로 승인하지 않습니다. 장마감 run 완료는 원장 동결 완료를 뜻할 뿐 보고서 집계 완료를 뜻하지 않으므로, `stock_post_close_cycle`을 `close_run_id`로 조인해 phase가 `REPORTS_AGGREGATED` 이상인 결과만 읽습니다. 종목별 `REQUIRES_NEW` 커밋 중 장애가 나도 이전 완료 거래일을 계속 제공하고 부분 생성된 당일 데이터는 숨깁니다.

계좌 일일요약 재구축은 30초 증분 flush와 같은 `execution-account-day-summary-flush` 전역 락을 사용합니다. 락 행을 장시간 미커밋으로 보유하지 않도록 다음 세 트랜잭션을 분리합니다.

1. 락 획득 후 즉시 커밋
2. 기존 일자 삭제와 정확 요약 INSERT를 한 원자적 트랜잭션으로 실행
3. 성공·실패와 무관하게 락 해제 후 즉시 커밋

다른 배치 인스턴스의 늦은 delta는 완료된 daily account snapshot을 확인한 flush가 폐기합니다. 이 요약은 정규장 화면을 위한 파생 데이터일 뿐 장마감 정산의 권위 원장이 아니며, 실패 시 다음 야간 재구축에서 원본 daily snapshot으로 복원할 수 있습니다.

Spring Batch flow 전이는 Step별 성공·실패 상태에 따라 구성할 수 있습니다. 다만 18시부터 다음 날 06시까지를 단일 장기 실행 Job으로 만들지 않고, business DB의 cycle 상태가 여러 Job을 연결하는 권위 있는 coordinator가 되어야 합니다.

---

## 8. 18시 경계 동시성 설계

수정 대상은 체결 worker만이 아닙니다.

- 사용자 신규 주문: `TradingService.placeOrder()`
- 사용자 주문 정정: `TradingService.amendOrder()` — 현재 장 상태 재검증 없음
- 자동 참여자 주문: `AutoMarketOrderExecutor.placeOrder()`
- 상장주관사 주문
- 내부 주문장 체결: `InternalOrderBookExecutionService`
- 최종 후보 조회: `OrderBookExecutionReader.findBestMatchCandidate()`

모든 신규·정정·체결 트랜잭션의 잠금 순서를 통일합니다.

```text
종목 session fence
→ 계좌 ID 오름차순
→ (account_id, symbol) 보유 row
→ 정확한 주문 PK 오름차순
→ 상태 갱신·원장 INSERT
```

거래 트랜잭션은 fence row를 `FOR SHARE`로 잠그고 커밋까지 유지합니다.

- `business_date` 일치
- `session_epoch` 일치
- `state=OPEN`
- 현재 권위 시각이 REGULAR
- 주문 상태가 체결 가능

장마감은 같은 fence를 `FOR UPDATE`로 잠급니다. 기존 공유 잠금이 해제될 때까지 기다린 뒤 `CLOSING`으로 전환합니다.

거래량 증가를 고려하면 이 검증을 여러 쿼리로 나누면 안 됩니다. 주문·체결 hot path는 종목당 한 번의 정확한 복합 PK 조회만 추가하는 것을 원칙으로 합니다.

- fence·시장 설정·활성 거래일·시뮬레이션 시계 입력을 한 SQL에서 조회
- MySQL은 `FOR SHARE OF f`로 `stock_market_session_fence` 별칭 `f`만 잠금
- `stock_market_business_state`, `stock_simulation_clock`, 시장 설정 행은 조회하되 전역 공유 잠금을 잡지 않음
- 사용자 주문의 기존 세션·시장 설정 중복 조회는 제거하고 최종 fence 검증으로 통합
- 취소·정정 결합 조회는 `side`도 함께 반환하고, 매도 주문이면 실제 주문 PK를 잠그기 전에 보유 row를 잠금
- 이미 잠근 계좌·보유 객체를 예약 반환·정정 로직에 전달하여 계좌와 보유를 다시 조회하지 않음
- 자동 주문은 개별 계획 주문마다 조회하지 않고 트랜잭션의 고유 symbol마다 한 번만 조회
- 체결은 후보 탐색 전에 해당 symbol fence를 한 번 잠그고 체결 커밋까지 유지
- fence 조회는 `(market_type, symbol)` PK equality여야 하며 범위 검색과 `SKIP LOCKED`를 금지

이 구조에서 같은 종목의 거래끼리는 공유 잠금이므로 서로 직렬화되지 않습니다. 배타 잠금이 필요한 시점은 장마감의 `OPEN → CLOSING` 전환뿐입니다.

주의점:

- Redis symbol lock은 중복 작업 감소용으로만 유지
- 정확성 기준은 DB fence
- `SKIP LOCKED`는 signal·work queue claim에만 사용
- 거래 원장 검증에는 `SKIP LOCKED`를 사용하지 않음
- 범위 `FOR UPDATE` 대신 정확한 PK 잠금 사용
- `READ COMMITTED` 전환은 잠금 순서·재검증 테스트 이후 검토

MySQL REPEATABLE READ에서는 범위 검색이 next-key/gap lock을 만들 수 있으므로 PK 기반 잠금이 중요합니다.

주문 취소는 장마감 후에도 허용할 수 있지만, 이미 close cycle이 취소한 주문을 사용자가 다시 반환 처리하지 않도록 idempotency가 필요합니다.

---

## 9. 모든 스케줄 작업의 목표 배치

| 작업 | 목표 구간 | 변경 |
|---|---|---|
| 시뮬레이션 heartbeat | 항상 | 유지 |
| 주문·정정·체결 | REGULAR | 최종 트랜잭션 fence |
| 자동 참여자 주문 | REGULAR | chunk마다 fence 재검증 |
| 상장주관사 공급 | REGULAR | fence 적용 |
| 자동 주문 만료 | REGULAR | 최대 100건 exact-PK/set-based 청크, 장마감 이후 잔여분은 close 취소에 통합 |
| 전체 장마감 | 18:00 | 최우선 전용 executor |
| 포트폴리오 정산 | 18:10 이후 | 스냅샷 기반 |
| 현금배당·청약·납입 | 00:00 이후 | action processing ledger |
| 자동 월급/자금 지급 | 00:00 이후 | 정규장 실행 금지 |
| 차트·일별 보고서 집계 | 00:00~04:30 | close snapshot/watermark 사용 |
| 빈 보유 정리 | 02:00 이후 | 필수 정산·기업행사 후 |
| 권리락·분할·신주상장 | 04:30 이후 | PRE_OPEN 전용 |
| 비주문장 기준가격 | 04:30 이후 | 기업행사 다음 |
| 일일 regime | 가격 준비 후 | 1일 1회 |
| 프로필 스케줄·Redis 큐 | 05:30 이후 | regime 완료 후 |
| readiness | 05:30 이후 | 실패 시 개장 차단 |
| DB signal 폴링 | 항상 | 유형별 `eligible_at` 적용 |
| 런타임 control 변경 | 항상 | 즉시 |
| 체결 계좌 일일요약 | 재설계 | 인메모리 권위 제거 |

DB signal 폴링 자체는 계속 실행해도 됩니다. 단, 시기상 실행할 수 없는 작업은 FAILED가 아니라 `DEFERRED`로 두고 다음 실행 가능 시각을 표시해야 합니다.

시간대 배치는 scheduler만 신뢰하지 않습니다. 기업행사 현금 단계, PRE_OPEN 수량·가격 변환, 자동 월급·정기 입금 서비스는 실제 대상 계좌·권리·현금흐름을 읽기 전에 작은 시장 설정/fence 테이블에서 열린 시장이 없는지 다시 확인합니다. coordinator 우회 실행이나 Spring Batch 재시작 시 시장이 열려 있으면 실패시켜 phase를 완료 처리하지 않으며, 이 admission guard는 `stock_order`와 `stock_execution`을 읽지 않습니다.

기본 coordinator가 켜진 경우 독립 자동 월급 scheduler는 clock·runtime-control·JobRepository를 읽기 전에 즉시 반환합니다. coordinator를 명시적으로 끈 호환 모드도 `PRE_OPEN(00:00~06:00)`에서만 runtime control과 Job을 실행하고 `REGULAR`·`AFTER_CLOSE`에는 반환합니다. 수동 신호는 백엔드의 `eligible_at`과 cycle phase gate를 통과한 `PRE_OPEN` 또는 운영상 직접 요청된 `AFTER_CLOSE`에서 실행할 수 있지만 `REGULAR`에서는 Spring Batch metadata를 만들기 전에 `SKIPPED/DEFERRED` 처리합니다. 따라서 거래량이 많은 정규장에는 자동·수동 월급 모두 계좌 잠금, 현금흐름 조회, JobRepository commit을 만들지 않습니다.

각 stage는 같은 검사를 별도 짧은 preflight 트랜잭션으로 먼저 수행해 freeze/open 경계가 이미 시작됐다면 프로필·계좌·권리·현금흐름 후보를 읽지 않습니다. 그러나 이 검사를 stage 진입 때 한 번으로 끝내지는 않습니다. 자동 월급과 기업행사의 각 `REQUIRES_NEW` 업무 청크는 계좌·보유·권리·종목 행을 잠그기 전에 `stock_market_business_state` 한 행을 공유 잠금하고, enabled 시장/fence가 다시 `OPEN`되지 않았는지와 현재 full-market cycle이 `OPEN`, `CLOSE_REQUESTED`, `ORDER_ENTRY_CLOSED`, `EXECUTION_DRAINED`가 아닌지를 한 소형 제어 쿼리로 재검증합니다. 먼저 시작한 저빈도 청크는 장마감 또는 개장의 배타 잠금이 기다린 뒤 해당 경계 이전 원장에 포함하고, 경계가 먼저 커밋되면 뒤 청크는 원장 쓰기 전에 실패합니다. 추가 비용은 stage preflight 1회와 저빈도 청크당 제어행 공유 잠금 1회·시장/freeze 제어 조회 1회이며 주문·체결·호가 원장은 읽지 않습니다. 이 전역 허가는 사용자 주문, 자동 주문, 체결 worker에는 절대 추가하지 않아 정규장 TPS와 지연을 보호합니다.

동일 장벽은 PREOPEN 시장 데이터의 종목별 가격 쓰기와 빈 보유 row 정리에도 적용합니다. 시장 데이터 provider 외부 호출은 DB 트랜잭션 밖에서 수행하고, 실제 `stock_price`·tick 쓰기 직전에만 짧은 허가 트랜잭션을 사용합니다. coordinator를 끈 호환 market-data refresh도 실제 가격 쓰기 직전에 singleton business-state 행을 공유 잠금하고 `OPEN`~`EXECUTION_DRAINED` close cycle이 없는지 확인합니다. MySQL 주문·체결은 `FOR SHARE OF f`로 종목 fence만 잠그므로 이 공유 permit과 직렬화되지 않으며, close의 배타 business-state 잠금과만 경합합니다. 따라서 먼저 시작한 갱신은 close가 기다렸다가 스냅샷에 포함하고, 먼저 시작한 close는 뒤 가격 쓰기를 `LEDGER_FROZEN` 전까지 거부합니다. PREOPEN strict 경로는 대상 종목/provider 조회 전에도 OPEN을 한 번 확인해 잘못 열린 장에서 외부 호출과 대형 후보 조회를 만들지 않습니다.

자동 월급의 runtime 비활성은 EOD 실패가 아니라 업무 정책상 지급 없음입니다. 따라서 `PORTFOLIO_SETTLED → OVERNIGHT_CASH_APPLIED`는 0건 `COMPLETED`로 전진하고 다음 장 준비를 막지 않습니다. 반대로 수동 지급 signal은 실행 가능 시각이 지났더라도 해당 거래일 full-market cycle이 최소 `PORTFOLIO_SETTLED`에 도달하기 전에는 claim하지 않습니다. 이 조건은 signal claim SQL에서 unique cycle key를 확인하므로 계좌·현금흐름·주문·체결 테이블을 읽지 않고, 대기 중 attempt를 증가시키거나 불필요한 JobRepository 이력을 만들지 않습니다.

수동 월급의 `QUEUED · 0건`도 다음처럼 바뀌어야 합니다.

- 대기 중: `00:10 실행 예정`, 처리 건수는 `-`
- 실행 중: 현재 대상/진행률
- 완료: 실제 처리 건수
- 실패/연기: 사유와 다음 재시도 시각

---

## 10. 자동 입금 정책

신규·수정 운영 설정은 `DAY`/`MONTH`/`YEAR`만 허용합니다. 기본 coordinator 모드에서는 `PORTFOLIO_SETTLED` 이후 T+1 00시에 due-sweep를 한 번만 실행하고, 각 계좌는 설정 간격이 지난 경우 최대 한 번 지급되며 지나간 여러 회차는 소급 지급하지 않습니다. 과거 row를 읽기 위한 `SECOND`/`MINUTE`/`HOUR` enum·표시 포맷은 호환을 위해 유지하지만 API와 관리자 입력은 새 sub-day 값을 만들지 않습니다. coordinator를 끈 호환 모드도 PRE_OPEN에서만 현실 5분 주기로 검사합니다.

이 선택은 정규장 주문·체결과 계좌 잠금·JobRepository commit을 경쟁하지 않으며, 실제 거래일 EOD 현금흐름이라는 현재 모델과 UI 의미를 일치시킵니다. 과거 sub-day cadence를 재현하거나 금액 원장을 여러 번 소급 생성하는 실험 모드는 구현하지 않습니다.

권장 기본 정책은 다음과 같습니다.

- 운영 프로필: 최소 `DAY`
- 수동 지급: 정규장 실행 금지, 00시 이후 `eligible_at`
- 시뮬레이션 실험 프로필: 이번 운영 경로에는 HOUR 이하를 허용하지 않음. 필요하면 별도 데이터·API·기능 플래그와 부하검증을 가진 독립 실험으로 설계
- 밀린 지급은 무제한 catch-up하지 않고 정책별 최대 회수 제한
- T+1 야간 지급은 T일 정산에 포함하지 않고 T+1 가용 현금으로 반영

현재 구현 상태는 **소스 완료, 운영 재기동 검증 대기**입니다. 2026-07-16의 “모든 작업에서 정규장 주문·체결 부하를 만들지 말라”는 최상위 지시에 따라 다음 1안을 채택했습니다.

1. 백엔드의 참여자/프로필 공통 정책에서 양수 현금 설정의 `SECOND`·`MINUTE`·`HOUR`를 거부한다.
2. 관리자 신규·일괄·수정 폼은 `DAY`·`MONTH`·`YEAR`만 제공하고 payload schema도 동일하게 제한한다.
3. 과거 값의 enum·응답 표시 호환은 유지하되 일반 저장 과정에서 자동 변환하지 않는다.
4. 지급은 야간 거래일당 최대 한 번, catch-up 없음으로 유지한다.

이 정책은 source/API/UI 제한이며 실 DB 값을 UPDATE하지 않습니다. 향후 sub-day 실험을 다시 도입하려면 운영 설정을 재사용하지 않고 PRE_OPEN 전용 bounded 청크, `(account_id, due_at, reason)` 멱등성, 일별 최대 지급 회수와 별도 거래량 A/B를 먼저 설계해야 합니다.

2026-07-16 운영 MySQL 읽기 전용 확인에서는 활성 개별 참여자 중 정기 지급액이 양수인 11건이 모두 `DAY`, 정기 지급액이 양수인 프로필 기본값 26건도 모두 `DAY`였습니다. `SECOND`·`MINUTE`·`HOUR` 양수 설정은 0건이므로 정책 전환에 데이터 자동 변환이나 소급 지급이 필요하지 않습니다. 운영 ALTER·재기동 뒤에도 이 불변식을 다시 확인하되 에이전트가 데이터를 자동 재작성하지 않습니다.

---

## 11. 성능 리팩터링

### 11.1 18시 hot path에서 제거할 것

다음은 장마감 동결 트랜잭션에서 빼야 합니다.

- 계좌별 전체 일일 체결 집계
- 보고서용 순위·수급 집계
- 차트 캔들 재집계
- 장기 기간 통계
- 기업행사 전체 sweep
- 프로필 큐 정리
- 비주문장 현재가 갱신

18시에는 다음만 수행합니다.

1. fence 전환
2. in-flight drain
3. 취소 전 주문 상태 캡처
4. 미체결 취소·예약 반환
5. 계좌·현금·보유·종가 스냅샷
6. 대사와 동결 완료

`stock_close_price_snapshot.last_execution_id`를 채우기 위한 당일 `stock_execution` 범위 `GROUP BY`도 18시 hot path에서 제거합니다. 종가·평가가격은 `stock_price`로 즉시 동결하고, 마지막 체결 ID와 거래량 집계는 야간 보고서 Step에서 계산합니다. 따라서 장마감 시간은 당일 체결 누적 건수에 비례하지 않아야 합니다.

계좌 외부 순입금도 매 장마감마다 `stock_account_cash_flow` 전체 이력을 다시 `GROUP BY`하지 않습니다. 직전 full-market cycle에 모든 계좌가 공유한 전역 PK watermark를 저장하고 다음 cycle은 `(previous_watermark, current_watermark]` 범위의 신규 원장만 집계합니다. 첫 도입 cycle만 기준값 생성을 위해 전체 이력을 읽으며, 이후 18시 비용은 전체 원장 보존기간이 아니라 해당 거래일 신규 현금흐름 수에 비례합니다. 직전 cycle은 auto-increment ID가 아니라 `business_date < current.business_date` 중 가장 가까운 완료 full-market cycle로 선택합니다. 오래된 누락 거래일을 나중에 복구하면 ID 생성 순서와 거래일 순서가 달라질 수 있기 때문입니다. 이 조회는 `stock_post_close_cycle(scope_type, scope_key, business_date, status, id)` 제어 인덱스만 사용하며 주문·체결 원장을 읽지 않습니다.

계좌 스냅샷은 기본 500계좌씩 생성하므로 watermark 범위를 계좌 청크 수만큼 PK로 다시 훑지 않게 `stock_account_cash_flow(account_id, id)`를 사용합니다. 청약 예약금도 `stock_corporate_action_entitlement(account_id, status)`로 해당 계좌·`SUBSCRIBED` 권리만 읽고, 반환 후 현금 대사는 `stock_close_account_snapshot(close_cycle_id, reconciliation_status, account_id)` keyset으로 진행합니다. 이 세 인덱스의 쓰기 비용은 현금흐름·권리·EOD 스냅샷 생성에만 발생하고 주문·체결 원장의 정규장 쓰기 증폭은 0입니다.

### 11.2 주문·체결 hot path 부하 예산

EOD 정확성을 위해 일반 거래 경로에 무제한 쿼리와 잠금을 추가하지 않습니다.

| 경로 | 허용하는 추가 DB 왕복 | 잠금 범위 | 금지 사항 |
|---|---:|---|---|
| 사용자 신규 주문 | symbol당 1회 | fence PK 공유 잠금 | 전역 거래일/시계 행 잠금, 별도 세션 재조회 |
| 사용자 취소·정정 | 주문당 1회 | 소유 주문 PK 조회 + fence PK 공유 잠금을 한 SQL로 결합 | 계좌·주문 descriptor 사전 개별 조회 |
| 자동 참여자·상장주관사 주문 | 고유 symbol당 1회 | fence PK 공유 잠금 | 계획 주문 건별 fence 조회 |
| 내부 주문장 체결 | 체결 트랜잭션당 1회 | 해당 symbol fence PK 공유 잠금 | 후보별 fence 재조회 |
| 장마감 | 대상 symbol당 1회 | symbol 정렬 후 fence PK 배타 잠금 | 전 테이블 범위 잠금 |

표의 수치는 EOD/session 정확성을 위해 기존 업무 경로에 추가되는 왕복 예산입니다. 자동 주문 생성의 업무 SQL 자체는 한 bounded 청크에서 `계좌 상태 PK 잠금 1회 → 실제 매도 보유 복합키 잠금 0~1회 → 매수 현금 CASE UPDATE 0~1회 → 매도 예약수량 CASE UPDATE 0~1회 → 주문 multi-row INSERT 1회`로 고정합니다. 매수/매도 집합이 비어 있으면 해당 문장은 실행하지 않으며, 참여자 또는 계획 주문 수만큼 예약 UPDATE·INSERT 왕복을 반복하지 않습니다. 최대 100명·800계획 주문 제한을 높이려면 MySQL 패킷 크기, prepare 파라미터 수, 계좌/보유 잠금 대기, 주문 API와 체결 p95/p99를 같은 데이터로 먼저 검증해야 합니다.

due 참여자 조회는 profile마다 실행되므로 한 쿼리만 제한해서는 전체 run 예산이 profile 수만큼 다시 늘어납니다. 현재는 실제 claim profile 수까지 분모에 포함해 전체 중간 결과를 `profile-count × participant-limit × symbol-count` 기본 2,000행 안으로 제한합니다. 기본 9 profile이 모두 due라면 3종목은 profile당 74명으로 약 1,998행, 100종목은 profile당 2명으로 약 1,800행입니다. row budget으로 최소 한 profile·한 participant도 감당할 수 없는 경우에는 profile claim을 확대하지 않으며, 활성 종목 수 자체가 budget보다 크면 조합 SQL을 실행하지 않고 `stock.auto.market.candidate.budget.skipped`를 증가시키고 상태가 회복될 때까지 WARN을 한 번만 기록합니다. 실제 주문 계획·저장은 별도의 25계좌·800주문 청크 상한을 다시 적용합니다.

신규 자동 참여자 스케줄 생성과 프로필 정책 변경도 참여자별 JDBC 쓰기를 반복하지 않습니다. 고유 user key를 최대 500개씩 조회하고, 누락 스케줄은 최대 100행 explicit multi-row INSERT, 변경된 profile·interval·priority는 최대 100행 CASE UPDATE로 반영합니다. 501명이 동시에 추가돼도 스케줄 쓰기는 501회가 아니라 6문장이고, 100명의 메타데이터 변경은 1문장입니다. 이 작업은 주문 원장을 직접 잠그지 않지만 같은 DB connection·redo·commit 자원을 사용하므로 `AutoParticipantOrderScheduleServiceVolumeTest`로 경계를 고정합니다.

schedule lease claim도 후보 참여자 수만큼 원격 UPDATE를 반복하지 않습니다. 최대 500개의 due user key를 한 조건부 UPDATE로 claim하고, 모두 갱신된 정상 경로에서는 추가 조회가 없습니다. 일부 행만 다른 인스턴스와 경합한 경우에만 현재 owner의 lease를 한 번 조회합니다. 따라서 참여자 수가 늘어도 claim 왕복 수는 정상 1회, 경합 시 2회로 고정됩니다.

주문 저장 뒤 schedule 완료도 기존 참여자별 UPDATE 루프를 사용하지 않습니다. 고유 user key를 최대 100명씩 묶고 `profile_type`, 공통 `last_run_at`, user별 `next_run_at`, interval, priority, lease 해제를 하나의 CASE UPDATE로 커밋합니다. 실제 주문 생성은 기본 25명 청크이므로 이 추가 쓰기는 청크당 1문장입니다. `AutoParticipantOrderScheduleServiceVolumeTest`는 100명 lease 완료가 한 UPDATE로 처리되고 100행 모두 완료되는지를 검증합니다.

모멘텀 기준가격 조회는 due 참여자가 하나 이상일 때만 실행하며, `(symbol, price_time)` 인덱스의 역순 `LIMIT 1` 서브쿼리를 종목별로 사용합니다. 윈도우 정렬을 다시 도입하지 않되 `UNION ALL` 한 문장도 최대 100종목으로 분할합니다. 100종목까지는 기존 1회 왕복을 유지하고, 그 이상은 bounded SQL 여러 개로 처리하여 1,000종목이 하나의 거대 SQL·파라미터 패킷으로 변하지 않게 합니다.

주 랜덤 regime와 30분 보조 modifier는 현재 거래일·phase·window의 기존 값을 최대 500종목씩 PK/보조 인덱스로 읽고, 누락분만 최대 500행 multi-row로 저장합니다. 동일 구간의 5초 auto-market run은 읽기 2종류만 수행하고 쓰기·중복 키 예외를 만들지 않습니다. 501종목 최초 생성은 일별 regime 2문장과 modifier 2문장으로 제한되며 같은 구간 재실행은 쓰기 0문장입니다. `AutoMarketDailyRegimeServiceVolumeTest`가 이 경계를 고정합니다. 이 경로는 주문·체결 원장을 읽거나 잠그지 않지만 같은 DB connection과 commit 자원을 공유하므로 steady-state 0-write를 성능 불변식으로 취급합니다.

상시 worker와 scheduler의 사전 admission gate는 엄격한 거래 트랜잭션 fence를 대체하지 않지만, 시장이 닫힌 동안 주문 큐·후보·계좌 쿼리로 내려가지 않게 합니다. 이 gate는 `stock_order`·`stock_execution`을 전혀 읽지 않습니다.

- 체결 worker 수가 2개 이상이어도 공유 캐시로 기본 최대 초당 1회만 조회
- worker gate 캐시 기본값 1초; 짧게 줄이는 대신 최종 거래 트랜잭션 fence가 정확성을 담당
- worker의 runtime-control refresh는 정상 상태에서 제어 PK SELECT만 수행하고 명시적 쓰기 transaction/commit을 열지 않음. 최초 row 생성·설정 동기화와 관리자 변경만 쓰기 허용
- 자동 주문 5초, 만료 10초, 상장주관사 공급 10초, fallback 체결 30초의 각 scheduler 진입당 최대 1회. fallback은 최소 10초보다 짧게 설정할 수 없고 Redis 누락 복구에만 사용
- 자동시장 서비스 내부의 세션 재검증도 같은 clock snapshot으로 수행하고, 종목별 만료·상장주관사 주문은 잠긴 fence의 `businessEffectiveAt`을 재사용하여 종목 수에 비례한 clock singleton 재조회 금지
- 자동 주문은 Redis ready-profile due 존재 여부를 전체 종목/보고서 설정 조회보다 먼저 확인. due가 없으면 auto-market service 업무 DB 쿼리 0회이며, due 확인 후 실제 profile claim이 성공한 경우에만 프로필 정책과 regime 조회
- 자동 참여자 주문 계획의 최근 배당 기간 계산도 상위 run의 `businessEffectiveAt`을 전달받아 사용하며, profile/symbol shard마다 시계 singleton을 다시 읽지 않음
- ready-profile 큐는 활성 DB 스케줄의 profile 집합과 주기적으로 맞추고, 존재하지 않는 profile은 재등록하지 않음. 미래 lease는 `max(next_run_at, lease_until)`을 다음 ready 시각으로 사용해 실패 profile의 1초 빈 재시도 폭주를 막음
- 원시 시뮬레이션 세션이 REGULAR가 아니면 short-circuit하여 DB gate도 조회하지 않음
- gate 장애는 fail-closed로 처리하고 주문 후보 탐색이나 체결을 시도하지 않음
- `stock.trading.session.fence.duration`과 `stock.orderbook.session.fence.duration`은 DB 왕복을 추가하지 않는 in-memory 계측으로 운영하며, 종목·계좌 ID를 tag로 남기지 않는다. 이 timer의 p95/p99와 주문 API·체결 worker 전체 timer를 함께 봐야 fence 자체 지연과 이후 원장 지연을 구분할 수 있다.

세션 gate SQL의 실행계획은 반드시 `PRIMARY` 또는 동등한 PK point lookup이어야 합니다. MySQL에서는 `FOR SHARE OF f`를 사용해 join된 전역 상태·시계 행을 잠그지 않습니다. H2의 `FOR UPDATE`는 테스트 대체 문법일 뿐 운영 잠금 설계로 해석하지 않습니다.

세션 상태 scheduler의 10초 polling은 정확성 fence와 별개입니다. 이미 동일 거래일로 동기화된 정규장에서는 시뮬레이션 clock 한 번과 config/fence 소형 테이블만 읽어 기대 상태를 비교하고 0건 반환합니다. 순수 clock·fence admission 조회는 명시적 쓰기 트랜잭션을 열지 않으며, 정규장에는 미정산 cycle 조회도 하지 않습니다. `stock_market_session_fence`의 전 종목 `FOR UPDATE`와 쓰기 transaction은 실제 상태 전환 시 한 번만 수행합니다. 따라서 평상시 polling이 주문·체결의 `FOR SHARE`와 주기적으로 충돌하거나 session epoch/version을 불필요하게 갱신하지 않습니다. 장중 `HALTED`, 당일 수동 `CLOSED`, 비활성 종목도 기대 CLOSED 상태로 인정해 한 종목의 정지 때문에 다른 모든 fence를 다시 잠그지 않습니다.

사용자 주문은 가격·틱·수량처럼 쓰기 없는 사전 검증을 먼저 끝내고, 계좌·보유·주문 잠금 직전에 fence를 획득합니다. 체결 후보 탐색은 빈 결과의 transaction/commit 비용을 없애기 위해 잠금 없이 먼저 수행할 수 있지만, 그 결과는 권위 있는 체결 승인이 아닙니다. 실제 변경 트랜잭션은 fence를 획득한 뒤 계좌·보유·선택 주문 PK를 순서대로 잠그고 상태·가격·상대 계좌를 모두 다시 검증해야 합니다. fence는 이 재검증부터 커밋까지 보유하며 같은 symbol의 다른 거래와는 공유 잠금이라 상호 차단하지 않습니다.

체결의 두 계좌는 별도 PK 조회 두 번으로 잠그지 않고 `id IN (?, ?)`·`ORDER BY id` 단일 `FOR UPDATE`로 잠급니다. 이에 따라 `fence → 계좌 → 매도 보유 → 정확한 주문 PK` 순서는 그대로 유지하면서 원격 DB 왕복은 체결당 1회 감소합니다. 계좌 두 건이 모두 조회되지 않으면 이후 보유·주문 잠금과 원장 INSERT를 실행하지 않습니다.

취소·정정은 주문 종목과 방향을 알아내기 위한 JPA 계좌 조회와 주문 엔티티 조회를 따로 추가하지 않습니다. 주문 PK, 계좌 `user_key` 유일키, 종목별 fence PK, 시장 설정 PK, 시계 singleton을 한 SQL에서 point lookup하고 MySQL에서는 `FOR SHARE OF f`로 fence 행만 잠급니다. 이 조회가 기존 취소 시각 조회를 대체하므로 원장 경로의 왕복 수를 늘리지 않습니다. 이후 계좌를 잠그고, 매도 주문이면 `(account_id, symbol)` 보유 row를 먼저 잠근 다음 정확한 주문 PK를 잠급니다. 예약 반환은 이미 잠긴 계좌·보유 객체를 재사용하며, 최종 주문 행의 소유권·종목·시장·방향·상태를 다시 검증합니다. 따라서 체결의 `계좌 → 보유 → 주문` 순서와 같아져 매도 취소·정정의 `주문 ↔ 보유` 데드락 순환을 제거합니다.

계좌 탈퇴와 자동 참여자 철회처럼 계좌의 모든 미체결 주문을 정리하는 저빈도 관리 경로도 같은 잠금 순서를 사용합니다. 호출자는 먼저 계좌 행을 배타 잠금합니다. `AccountOrderCleanupService`는 `PENDING`과 `PARTIALLY_FILLED`를 분리하여 상태 equality prefix를 유지하고, `(created_at, id)` keyset으로 후보를 최대 500건만 읽습니다. 전체 시장형 정리는 기존 `idx_stock_order_account_status_created`, 주문장 전용 정리는 기존 `idx_stock_order_market_status_account_time`의 고정 prefix와 InnoDB 보조 인덱스 PK 확장을 사용하므로 별도 `stock_order` 인덱스를 추가하지 않습니다. 각 청크에서 매도 종목만 정렬해 보유 row를 잠근 뒤 정확한 주문 PK를 오름차순으로 잠그고 재검증하며, 취소 UPDATE와 현금·예약수량 반환도 해당 청크에만 수행합니다. 동일 `created_at` 주문이 500건 경계를 넘는 경우에도 `id` tie-breaker로 누락 없이 진행합니다. 따라서 계좌 전체 미체결 주문을 JVM에 올리거나 주문당 보유 조회·주문당 UPDATE와 거대한 `IN` 절을 만들지 않고, 다른 계좌의 정상 주문·체결을 상태 범위 잠금으로 막지 않습니다.

이 경로는 탈퇴 상태 변경과 예약 반환의 원자성을 위해 호출자의 계좌 잠금과 하나의 외곽 트랜잭션을 유지합니다. 메모리·SQL 파라미터·개별 조회 범위는 500건으로 제한되지만 처리한 정확한 주문 PK 잠금은 커밋까지 유지되므로, 운영 A/B에서 한 계좌의 미체결 주문 p99가 5,000건을 넘거나 정리 transaction p95가 2초를 넘으면 동기 chunk 크기를 키우지 않습니다. 그 경우 `DETACHING/WITHDRAWING` 상태와 별도 야간 cleanup cycle을 도입해 청크별 커밋으로 전환해야 하며, 그 전까지 이 코드를 전체 시장 마감이나 종목 전체 취소에 재사용하지 않습니다.

다음 회귀 조건을 배포 차단 기준으로 둡니다.

- fence 도입 후 주문 API p95 증가가 5ms 또는 기존 p95의 10% 중 큰 값 이하
- 체결 한 건당 세션 관련 SQL은 1회 이하
- 정규장 fence shared-lock 대기 p95 2ms 이하
- `stock_market_business_state`와 `stock_simulation_clock`의 거래발 lock wait 0건
- 장마감 이외 fence exclusive-lock 대기 0건
- 주문·체결 TPS가 기준 부하 대비 95% 미만으로 하락하면 cut-over 금지

상장주관사 유동성 공급은 `stock_listing_auto_account_config`의 종목 PK 때문에 현재 종목당 설정 1건으로 한정되고, 만료 후보와 방향별 열린 주문도 각각 최대 200건만 읽습니다. 따라서 설정·주문을 무제한 JVM 적재하지는 않습니다. 다만 한 종목 transaction이 Redis symbol lock을 보유한 채 만료·초과 주문 정리·호가 상태·신규 주문을 처리하므로 다음을 별도 승인 지표로 둡니다.

- `stock.listing.auto.market` 종목 transaction p95 100ms 이하, p99 300ms 이하
- 해당 작업 때문에 발생한 execution symbol-lock skip 비율 1% 이하
- 하나의 10초 run이 전체 종목을 연속 처리하더라도 다음 run과 상시 겹치지 않을 것
- 한 run은 기본 100·최대 500종목의 결정적 시간 slice만 처리한다. 100종목 검증에서 위 기준을 넘으면 상한을 낮추거나 종목별 query read-model을 통합할 것
- 기존 hot-ledger 인덱스로 부족하다는 `EXPLAIN ANALYZE` 없이 listing 전용 `stock_order` 인덱스를 추가하지 않을 것

### 11.3 쿼리

- `DATE(created_at)=?`, `TIME(created_at)>=?` 금지
- `[startDateTime, endDateTime)` 범위 사용
- order/execution 일별 집계는 `business_date` 또는 범위 인덱스 사용
- 전체시장·종목 겸용 쿼리에서 `(? IS NULL OR symbol = ?)`로 실행계획을 공유하지 않고 SQL을 분리한다. 종목 단위 마감·수동 취소는 반드시 `symbol = ?`로 먼저 제한해 다른 종목 주문을 읽거나 잠그지 않는다.
- `stock_execution` 야간 보고서도 종목별 실행에서는 `source, symbol, executed_at` exact range를 사용한다. nullable symbol predicate나 전체 체결일 범위 스캔을 종목 수만큼 반복하지 않는다.
- 종목 시가·종가를 구하기 위해 하루 체결 전체에 `ROW_NUMBER()` 정렬을 두 번 수행하지 않는다. 집계는 `idx_stock_execution_source_symbol_time`, 최초·최종 BUY 가격은 `idx_stock_execution_candle`의 양방향 `LIMIT 1`, 계좌별 수급은 `idx_stock_execution_market_report_flow`를 사용한다. 이 세 인덱스는 기존 원장 인덱스이며 EOD 리팩터링이 새 체결 쓰기 인덱스를 추가하지 않는다.
- close 대상 open order ID를 먼저 고정
- 계좌별 상관 서브쿼리 제거
- snapshot writer는 chunk bulk UPSERT
- 정산 reader는 `(close_cycle_id, settlement_target, account_id)` 순서로 계좌 스냅샷만 paging하고, 보유 합계는 18시 동결 시 cycle당 한 번만 생성
- 보고서는 close cycle ID를 직접 조건으로 사용
- 누적 현금흐름은 전역 PK watermark 증분 범위를 사용하고 계좌별 `MIN(watermark)` 재검색을 금지
- watermark 증분 집계는 `(account_id, id)`에서 현재 계좌 청크와 PK 범위를 동시에 제한하여, 계좌 1만 개에서 500개 청크 20번이 동일 전역 PK 구간을 20번 전량 스캔하지 않게 함
- 청약 예약금은 `(account_id, status)`, 반환 대사 paging은 `(close_cycle_id, reconciliation_status, account_id)`를 사용하며, 신규 인덱스는 startup schema readiness에서 필수로 검증
- 야간 종목 보고서는 symbol 단위 `REQUIRES_NEW`로 커밋하여 한 종목 실패가 전체 장시간 트랜잭션으로 확대되지 않게 함
- 야간 종목·계좌 보고서 Step은 `(close_cycle_id, symbol)` 키셋의 마지막 완료 symbol을 기본 25개·최대 200개 cohort마다 Spring Batch `ExecutionContext`에 커밋한다. 재시작은 해당 체크포인트 뒤에서 이어가며, 체크포인트 직전 장애로 반복되는 범위는 현재 cohort 이하이다. cohort 안의 각 `(close_run_id, symbol)` 결과는 독립 `REQUIRES_NEW`에서 삭제·재작성되어 멱등이고, 이미 체크포인트된 앞 cohort나 전체 거래일을 지우고 첫 symbol부터 원본 `stock_execution`을 다시 집계하는 복구는 금지한다.
- 보고서 서비스 진입점도 cycle phase가 정확히 `CORPORATE_CASH_APPLIED`인지 재검증하여, scheduler 우회 호출이 freeze·정산과 `stock_execution` 범위 집계를 중첩하거나 완료 보고서를 다시 삭제하지 못하게 함
- coordinator용 launcher 전부가 full-market scope와 정확한 선행 phase를 제어행에서 한 번 더 검증한 뒤에만 업무 Job/task를 시작함. 잘못된 phase의 직접 호출은 `stock_order`·`stock_execution`·계좌·보유 테이블을 읽기 전에 거부하며, 준비 거래일도 반드시 `cycle.business_date + 1`인지 검증함
- 자동 월급·정기 입금은 기본 200계좌 단위 `REQUIRES_NEW`로 처리하여 최근 지급 조회 `IN` 크기, 연결 점유시간, 실패 롤백 범위를 함께 제한
- 자동 월급 대상 탐색도 `account_id > last_account_id order by account_id limit 200` keyset을 사용하여 전체 자동 참여자를 JVM에 적재하지 않음
- 자동 월급 지급 chunk는 중복 지급 window 검증 후 실제 대상만 계좌 PK 순으로 잠그고, 계좌 현금 CASE UPDATE와 현금흐름 multi-row INSERT 두 번으로 반영한다. 기존 계좌당 두 번의 원격 쓰기를 제거하고 두 쓰기 건수가 지급 대상 수와 다르면 transaction 전체를 롤백한다.
- 기업행사 현금/PRE_OPEN 변환과 자동 월급 서비스는 대상 원장 조회 전 DB-open admission guard를 실행하고, 시장이 열려 있으면 대형 조회·계좌 갱신 없이 즉시 실패
- 기업행사 due event 선택은 `action-batch-limit` 기본 25, 최대 200건으로 제한한다. 계좌 1만 개 기준 이벤트 하나가 기본 200계좌 청크 약 50개로 나뉘므로 due event 상한과 계좌 청크 상한을 곱한 commit 예산까지 고려한다. 한 번에 모두 처리되지 않으면 phase 후조건이 완료를 거부하고 coordinator가 같은 JobInstance를 재시작하며, 이벤트 목록을 무제한 JVM 적재하거나 한 트랜잭션으로 합치지 않는다.
- 배당·자동청약·신주반영·분할은 기본 200계좌 keyset chunk와 계좌별 processing ledger를 사용하여 대량 계좌 전체를 한 트랜잭션에서 잠그지 않음
- 현금배당의 계좌·현금흐름·권리·processing 원장 반영은 200계좌당 고정된 5개 set-based SQL 수준으로 유지한다. 계좌는 PK 오름차순으로 먼저 잠그고 네 쓰기 건수 모두 선택 entitlement 수와 일치해야 같은 트랜잭션을 커밋한다. 따라서 일부 현금만 지급되거나 처리 원장만 누락된 상태를 만들지 않으면서 계좌당 네 번의 원격 왕복을 제거한다.
- 자동청약은 계좌 후보를 읽은 뒤 `action → account PK 오름차순 → entitlement PK 오름차순`으로 잠근다. 주주배정은 후보·계좌·권리를 각각 한 번 조회하고 성공분을 세 번의 set-based write와 한 번의 processing insert로, 일반공모는 후보·계좌를 각각 한 번 조회하고 같은 고정 write 수로 처리한다. 성공 결정이 0건이면 현금·권리 writer를 호출하지 않는다. 청크 크기가 200에서 1,000으로 커져도 SQL 왕복 수가 계좌 수에 선형 증가하지 않으며, 수동청약의 잠금 순서와도 역전되지 않는다.
- 신주 반영도 entitlement별 `holding UPDATE/INSERT → entitlement UPDATE → processing INSERT` 루프를 금지한다. 기존 보유가 반드시 있어야 하는 무상/주식배당은 잠긴 account cohort에 CASE UPDATE하고 건수를 검증한다. 신규 보유가 가능한 유상증자는 현재 보유 원가와 incoming 청약 원가를 합산하는 다중 UPSERT를 사용하며, UPSERT의 MySQL affected-row 값은 update 시 2가 될 수 있으므로 반환 건수 대신 후속 권리·processing 건수와 트랜잭션 성공으로 검증한다.
- 액면분할은 선택된 account ID를 PK 순서로 잠그고 동일 symbol의 보유를 한 번에 갱신한다. 보유 잠금·갱신·processing 다중 INSERT가 모두 선택 계좌 수와 일치해야 커밋하며, 계좌별 반복 SQL과 대형 전체 보유 트랜잭션을 동시에 피한다.
- 권리락 entitlement 생성은 보유자 전체 `INSERT ... SELECT`를 금지하고, 이미 생성된 `(action_id, account_id)`를 제외한 200계좌씩만 커밋한 뒤 action 상태를 최종 전환
- 기업행사 chunk 재시작 후 전체 처리 건수는 현재 시도의 처리 수만 쓰지 않고 계좌별 processing ledger 완료 수를 합산하여 이전에 커밋된 성공분까지 포함
- 수동 종목 취소·상장폐지 주문 정리는 기본 500주문 chunk로 DB 트랜잭션과 연결을 반환하되, 종목 lock과 재검증으로 신규 주문 혼입과 이중 예약 반환을 차단. exact-PK 주문 취소, 계좌 현금 반환, 보유 예약수량 반환을 각각 단일 set-based UPDATE로 실행하여 주문당 JDBC 왕복을 금지
- 상장폐지 주문 chunk도 상태 인덱스 범위 `FOR UPDATE`를 사용하지 않고 `잠금 없는 exact-symbol 후보 → 계좌 PK → 매도 보유 PK → 주문 PK`로 처리. 선택·잠금·취소·반환 건수 중 하나라도 다르면 같은 트랜잭션을 롤백
- 전체 장마감의 symbol-lock 목록은 session fence·시장 설정·상장 종목 제어 테이블에서만 만들고, lock 준비를 위해 `stock_order`·`stock_holding`을 전체 스캔하지 않음
- 계좌 탈퇴·자동 참여자 철회는 잠긴 계좌 아래에서 상태별 `(created_at, id)` keyset 후보를 500건씩 읽고, 해당 청크의 매도 보유·정확한 주문 PK만 잠가 상태 변경·예약 반환한다. 전체 주문 JVM 적재, 주문당 DB 왕복, 무제한 `IN` 절과 신규 hot-ledger 인덱스를 금지
- PRE_OPEN 시세 provider 네트워크 호출은 business transaction 밖에서 수행하고 종목별 가격·tick DB 쓰기만 짧게 커밋. provider 지연이 DB connection·row lock 보유시간으로 전파되지 않음
- PRE_OPEN 시세 대상 조회는 `VIRTUAL_PRICE`의 현재 미체결 주문과 양수 보유를 각각 symbol별로 선집계한 뒤 UNION. DB 읽기량은 전체 과거 주문이 아니라 현재 활성 virtual 주문·보유 행에 비례하고, UNION 결과·provider 호출·가격 쓰기는 감시 종목 수에 비례한다. 현재 활성 virtual 주문 자체가 커질 때의 비용은 기존 `(market_type, status, symbol)` 인덱스로 제한하며 이를 이유로 주문장 `ORDER_BOOK` INSERT 인덱스를 추가하지 않는다.
- PRE_OPEN cycle 실행은 모든 대상 갱신이 성공해야 phase를 완료하고, 실패 시 성공 종목만 짧게 커밋한 뒤 동일 cycle에서 재시도한다. 재시도 tick은 `(symbol, price_time)` 기존 인덱스 범위의 `NOT EXISTS`로 멱등 처리해 provider 장애가 가격 tick 쓰기 증폭으로 이어지지 않게 한다.
- 기업행사 phase 후조건과 readiness는 기업행사·processing ledger만 집계하며 주문·체결 원장을 재스캔하지 않음
- 주문·체결 hot path에 필요한 신규 EOD 보조 인덱스를 임의로 `stock_order`에 추가하지 않음. 실제 MySQL `EXPLAIN ANALYZE`가 기존 PK/상태 인덱스로 부족함을 증명할 때만 주문 INSERT 비용과 함께 검토

#### 정규장 인덱스 쓰기 예산

현재 canonical DDL 기준 `stock_order`는 PRIMARY 외 UNIQUE/보조 인덱스 15개, `stock_execution`은 보조 인덱스 12개를 유지한다. 특히 `idx_stock_execution_candle`과 `idx_stock_execution_market_report_flow`는 여러 금액·수량 열을 포함한 covering index이므로, 체결 한 건마다 인덱스 페이지 쓰기 비용을 추가한다.

이번 EOD용 신규 ALTER 11개는 `stock_order`·`stock_execution`에 열이나 인덱스를 추가하지 않는다. 손익 read model 증분 ALTER 한 개는 서비스가 모두 종료된 유지보수 창에서 `stock_execution`을 한 번 읽어 과거 요약을 보정하지만 원장 정의와 정규장 쓰기 비용은 바꾸지 않는다. 기존 인덱스도 이름이 비슷하다는 이유로 즉시 삭제하지 않고 다음 절차를 거친다.

2026-07-16 운영 DB의 읽기 전용 사전 점검에서 `stock_execution_account_day_summary`에는 신규 손익 7개 컬럼이 모두 없었고 기존 요약은 9,378행이었다. 따라서 9번 ALTER의 조건부 backfill은 최초 적용 때 실제로 실행된다. `EXPLAIN FORMAT=JSON`은 현재 약 103만 건의 `stock_execution`을 기존 covering index로 1회 읽고 임시 그룹 집계를 만드는 계획을 선택했다. 이는 정규장에 상시 추가되는 비용이 아니라 서버가 모두 종료된 유지보수 창의 일회성 비용이며, 결과 행 수가 작은 현재 규모에서는 날짜별 stored procedure나 영구 migration cursor를 추가하지 않는다. 다만 이 조회는 실행시간을 측정한 `EXPLAIN ANALYZE`가 아니므로 적용 창에서 시작·종료시각과 임시 테이블/디스크 사용량을 기록하고, 30초 연결 제한을 받지 않는 MySQL client로 실행한다. 컬럼 DDL 뒤 backfill이 실패한 재시도도 복구하도록, 컬럼 최초 누락 여부뿐 아니라 소형 요약 테이블의 `gross_amount = buy_gross_amount + sell_gross_amount` 불변식을 확인해 default-zero 중간 상태만 다시 backfill한다. 정상 재실행은 103만 건 원장을 다시 읽지 않는다.

1. 동일 build·schema·데이터셋에서 주문 TPS, 체결 TPS, INSERT p95/p99, buffer-pool write, index page split을 기준선으로 저장한다.
2. `performance_schema.table_io_waits_summary_by_index_usage`와 실제 `EXPLAIN ANALYZE`로 정규장·야간·관리자 쿼리의 인덱스 사용을 모두 확인한다.
3. MySQL 8 검증 DB에서 후보를 한 개씩 `INVISIBLE`로 바꿔 주문·체결 쓰기 이득과 조회 회귀를 A/B 비교한다.
4. 최소 여러 시뮬레이션 거래일 동안 조회 타임아웃·full scan이 없고 쓰기 SLO가 개선된 후에만 삭제 ALTER를 별도 배포한다.
5. 현재 추정 주문 151만·체결 103만 이상 원장과 관련된 인덱스 DDL은 정규장 중에 임의 실행하지 않고, 사용자가 백엔드·배치 종료를 확인한 유지보수 창에서만 적용한다.

따라서 EOD 리팩터링의 성능 전략은 “조회를 빠르게 하기 위해 거대 covering index를 더 붙인다”가 아니라, 정규장 원장 집계를 없애고 close snapshot·야간 summary로 읽기 비용을 이동한 뒤 불필요한 쓰기 인덱스를 증거 기반으로 줄이는 것이다.

### 11.4 체결 계좌 일일요약

`ExecutionAccountDaySummaryAccumulator`는 체결 트랜잭션 커밋 후 DB를 쓰지 않고 비차단 account-day 메모리 병합만 수행하고, 명시적 maintenance scheduler가 bounded batch로 flush하도록 변경했습니다.

권장안:

- 장마감 정합성의 권위 소스에서는 제외
- pending 고유 account-day 상한과 flush row 상한을 두어 체결 폭증이 객체 수·DB flush 폭증으로 이어지지 않게 함
- 체결마다 `ConcurrentLinkedQueue`에 객체를 추가하지 않고 `(simulation_trade_date, account_id)` 키의 누적 execution/수량/gross/net/fee/tax/realized 값을 `ConcurrentHashMap.compute`로 즉시 병합함. 따라서 메모리와 flush 준비 비용은 체결 건수가 아니라 해당 간격의 활성 계좌 수에 비례함
- 한 flush는 최대 5,000 account-day row를 compare-remove로 drain하고 최대 500행 multi-row UPSERT 문장으로 나눔. Connector/J `rewriteBatchedStatements` 유무에 의존하는 `batchUpdate`를 사용하지 않으며, 계좌 수만큼 원격 문장을 전송하지 않음
- 한 flush의 모든 UPSERT 청크와 완료일 검증은 하나의 짧은 업무 트랜잭션으로 커밋함. 중간 청크 실패 시 이전 청크도 롤백한 뒤 drain한 누적값을 현재 map 값과 다시 병합하여 부분 커밋·동시 체결 유실·중복 가산을 막음
- pending account-day가 0이면 scheduler는 runtime-control 조회와 전역 DB job-lock 획득·해제까지 생략하여 거래가 없는 구간의 주기적 commit을 0으로 유지
- flush 실패 누적값은 같은 키에 재병합
- queue overflow는 파생 실시간 요약만 포기하고 원본 체결은 절대 실패시키지 않음
- 야간 보고서 단계에서 `[businessDate.atStartOfDay(), businessDate.plusDays(1).atStartOfDay())`의 `stock_execution` 범위만 읽어 정확한 일일 요약을 삭제·재구축. 모든 source를 합산하여 기존 사용자 손익 API 의미를 보존하고 `DATE()`·`TIME()` predicate를 사용하지 않음
- 프로세스 종료·overflow 후에도 기존 `(executed_at, account_id)` 인덱스 범위의 원본 체결에서 재구축 가능
- 증분 flush Job은 multi-instance 전역 job lock을 반드시 획득
- 야간 정확 재구축도 같은 lock을 획득하되, lock 획득 트랜잭션을 먼저 커밋하여 다른 scheduler가 미커밋 unique row 뒤에서 connection을 붙잡고 기다리지 않게 함
- daily snapshot이 완료된 거래일의 늦은 delta는 다시 UPSERT하지 않고 `discarded-after-finalization` metric으로만 기록
- 실시간 summary scheduler는 pending account-day 누적값이 있더라도 시장 fence가 닫힌 뒤에는 실행하지 않아 18시 원장 동결의 연결·commit과 경쟁하지 않으며, 야간 재구축이 최종 권위 값을 만든다
- 재구축 전 로컬 누적값의 해당 일자 제거는 account-day map의 조건부 remove 한 번으로 처리하여 비용을 체결 건수가 아닌 활성 계좌 키 수로 제한
- 재구축의 `DELETE + INSERT ... SELECT`는 한 transaction이므로 중간 실패 시 기존 정확 요약이 사라진 채 커밋되지 않음

빈 보유 row 정리는 회계·개장 정합성의 필수 단계가 아닌 선택적 유지보수로 분류합니다. 설정 또는 runtime control이 꺼져 있으면 0건 완료로 phase를 계속하고, 필수 PRE_OPEN 기업행사 변환은 별도로 실행합니다. 반면 서비스 종료 중의 `SKIPPED`는 성공으로 바꾸지 않아 종료 도중 cycle이 진전되는 것을 막습니다.

### 11.5 Spring Batch 메타데이터

현재 약 이틀 구간에만 3,600건 이상 JobExecution이 생성됐고 `BATCH_JOB_EXECUTION_PARAMS`는 약 138만 행입니다.

- 실제 due-work가 있을 때만 기업행사 Job 실행
- 시장 데이터 Job을 매분 기록하지 않고 phase/거래일 단위로 실행
- 체결·자동 주문 같은 고빈도 마이크로 작업은 계속 경량 실행기 사용
- 실행 횟수·처리량은 Micrometer
- 영속 이력은 phase별 Job과 주기적 요약만
- 성공 메타데이터 보존기간과 아카이브 정책 추가

JobRepository는 Job/Step 실행 상태와 재시작을 위한 핵심 저장소이므로 EOD Job에서는 유지하되, 모든 폴링 작업의 범용 실행 로그로 사용하지 않는 것이 맞습니다.

현재 소스에는 `BatchMetadataRetentionJob`과 `BatchMetadataRetentionService`를 추가했다. 이 작업 자체는 Spring Batch Job으로 실행하지 않는 cycle 소속 `LightweightBatchTask`이므로 자기 실행 metadata를 만들거나 삭제하지 않는다. `REPORTS_AGGREGATED` 이후 PRE_OPEN 유지보수 묶음에서만 공통 heavy-admission·job lock·cycle lease를 획득하고, 시장이 하나라도 OPEN이면 service 내부에서도 다시 거부한다. 실패해도 개장 준비를 막는 필수 회계 단계로 승격하지 않고 다음 cycle에서 재시도하며, 실패 instance 수는 저카디널리티 metric과 WARN으로 남긴다.

보존 기준은 metadata의 실제 `END_TIME`이므로 시뮬레이션 거래일이 아니라 현실 일수다. 기본값은 다음과 같으며 전체 기능이 기본 비활성이다.

- `enabled=false`
- `retention-real-days=30`
- 한 실행의 JobInstance 후보 최대 25, 코드 상한 100
- instance당 JobExecution 기본 최대 20, 코드 상한 50
- instance당 StepExecution 기본 최대 200, 코드 상한 500
- `purge-enabled=false`
- purge를 켤 때 비어 있지 않은 `purge-job-names` allow-list 필수

후보 SQL은 `COMPLETED`이고 cutoff보다 오래된 execution만 시작점으로 삼되, 같은 JobInstance에 `STATUS is null`, 미완료 상태, `END_TIME is null`, cutoff 이후 execution이 하나라도 있으면 전체 instance를 제외한다. archive 전에는 Spring Batch domain 객체를 다시 읽어 상태·종료시각·execution/step 상한을 재검증한다. 애플리케이션이 직접 실행하는 metadata 후보·archive SQL에는 10초 statement timeout을 적용해 잘못된 통계·실행계획이 같은 MySQL 호스트의 연결을 장시간 점유하지 않게 한다. compact archive에는 execution ID, instance ID, job name/key, 식별 파라미터 요약, 시작·종료·exit, Step read/write/commit/rollback/skip 합계만 저장하며 원본 context를 복제하지 않는다.

실제 purge는 Spring Batch 6.0.2 `JobRepository.deleteJobInstance`를 metadata transaction 안에서 호출한다. framework DAO가 Job/Step context → parameters → StepExecution → JobExecution → JobInstance 순서를 소유하도록 하고 애플리케이션이 FK 삭제 순서를 복제하지 않는다. 공식 문서가 경고하듯 미완료 instance나 context를 삭제하면 재시작 의미가 깨지므로 그런 행은 코드와 SQL 양쪽에서 제외한다. 완료 instance도 삭제되면 동일 identifying parameter가 새 instance로 인식될 수 있으므로, archive만 먼저 운영한 뒤 business idempotency가 검증된 job name만 allow-list에 넣는다.

`STOCK_BATCH_JOB_METADATA_ARCHIVE`와 `(STATUS, END_TIME, JOB_INSTANCE_ID)` 후보 인덱스는 `STOCK_BATCH_METADATA`에만 존재한다. `stock_order`·`stock_execution` 및 business connection pool을 읽거나 변경하지 않는다. 후보 인덱스의 write 비용도 native EOD Job metadata에만 발생하고 초단위 체결·자동 주문은 metadata를 생성하지 않는다. 그래도 같은 MySQL 호스트의 I/O를 공유하므로 운영 활성화 전후 metadata commit p95와 주문/체결 p95를 함께 A/B한다.

### 11.6 실행 자원 분리

- EOD coordinator: 단일 스레드
- `POST_CLOSE` scheduler pool은 정확히 1만 허용하고, 환경변수로 2 이상 지정하면 기동 실패. phase 병렬화를 통한 처리시간 단축보다 주문·체결 DB I/O 보호를 우선
- close freeze: 최우선 전용 executor
- settlement: 저동시성 chunk executor
- overnight maintenance: 최대 1개 무거운 Job
- Spring Batch Job뿐 아니라 경량 실행기로 유지한 가격 준비·빈 보유 정리·PRE_OPEN 프로필 큐도 `close_cycle_id`를 runner에 전달해 job lock과 cycle lease를 30초마다 함께 갱신한다. 처리량 증가로 3분 lease를 넘더라도 다른 배치 인스턴스가 같은 phase를 중복 실행하지 않게 한다. job lock과 공통 heavy-admission lock의 `lock_owner`는 서버 단위 값만 재사용하지 않고 물리 실행마다 별도 UUID token을 사용한다. 동일 JVM에서 만료 lock을 새 실행이 회수해도 이전 heartbeat·finally release의 owner 조건이 새 token과 일치하지 않으므로 새 실행의 lock을 연장하거나 삭제하지 못한다. 단발성 DB 오류는 다음 주기 성공 시 회복된 것으로 지우고, 업무 결과를 반환하기 직전에 job lock·heavy admission·cycle lease를 직렬화된 한 번의 최종 갱신으로 다시 검증한다. 실제 owner-qualified UPDATE가 실패한 소유권 상실만 phase 실패로 남기며, 모든 갱신은 제어행에만 적용되어 주문·체결 원장의 정규장 쓰기 비용을 늘리지 않는다.
- regular auto market: 별도 executor
- 자동 생성 executor는 core/max 1~16, queue 0만 허용하고 전체 run dispatcher는 정확히 1/1/0만 허용. 정규장 지연을 backlog로 숨기지 않고 포화 run을 즉시 건너뜀
- scheduler는 execution만 1~4를 허용하고 auto-market·maintenance·post-close·simulation-clock은 정확히 1 thread로 고정. pool 환경변수 증설로 DB 경합을 해결하지 않음
- DB signal claim: 경량 executor
- DB pool을 늘리기 전에 작업 중첩을 제한

단일 scheduler thread만으로는 충분하지 않다. DB signal processor는 maintenance thread에서 실행되므로 POST_CLOSE coordinator와 병렬 진입할 수 있다. 현재 구현은 모든 native Spring Batch Job과 cycle 소속 lightweight task가 `post-close-heavy-admission` DB lease를 먼저 획득하도록 하여 executor가 달라도 무거운 업무는 한 개만 허용한다. admission 획득 실패는 대상 Job의 Spring Batch metadata와 대형 원장 조회 전에 반환되며, lock TTL과 cycle lease는 기존 heartbeat가 함께 갱신한다. 체결 worker·자동 주문처럼 정규장 경량 task는 이 전역 admission에서 제외해 정상 거래를 직렬화하지 않는다.

maintenance scheduler는 현재 정확히 1 thread만 허용하지만, DB signal processor와 POST_CLOSE coordinator는 서로 다른 executor에서 진입할 수 있으므로 scheduler thread 수만으로 EOD 순서를 보장하지 않습니다. cycle phase와 `post-close-heavy-admission` lease가 executor 경계를 넘는 전역 admission control 역할을 해야 합니다.

### 11.7 목표 SLO

초기 목표값:

- `OPEN → CLOSING`: p95 250ms 이하
- in-flight drain: p95 2초 이하
- ledger freeze: p95 5초, 경고 10초, hard alert 15초
- 포트폴리오 정산: p95 15초 이하
- 30초 socket timeout: 0건
- close 이후 원장 변경: 0건
- 동일 cycle 재정산 hash 차이: 0건
- Job metadata 일일 증가량: 현재 대비 80% 이상 감소

timeout을 먼저 늘리지 말고, 위 구조를 적용한 뒤 예상치 못한 네트워크 지연에 대한 보조 안전망으로만 조정해야 합니다.

### 11.8 거래량 단계별 장마감 실행 전략

현재 구현은 close cycle마다 취소 대상 주문 ID와 반환 금액을 `stock_close_open_order_snapshot`에 먼저 고정한 뒤, 그 cohort만 처리합니다. 캡처 대상은 기존 `idx_stock_order_market_status_symbol`을 강제 사용하여 열린 주문이 존재하는 symbol만 찾고, `PENDING`·`PARTIALLY_FILLED`를 각각 `(market_type, status, symbol)` 고정 prefix 뒤 InnoDB 확장 PK `id` keyset으로 1,000건 단위로 읽습니다. 이 실행계획은 MySQL `EXPLAIN` 격리 테스트에서 filesort 비회귀까지 검사합니다. 따라서 진단 당시 145만 건이던 주문 이력이 현재 추정 151만 건 이상으로 늘어도 매일 과거 종료 주문 전체를 다시 읽지 않고, 정규장 주문 INSERT 비용을 늘리는 신규 원장 인덱스도 없습니다. 취소·예약 반환은 500건, 보유·계좌·대사는 계좌 500건을 기본 상한으로 항상 적용합니다. 이는 장마감 도중 새 주문이 섞이는 것을 막고 거래량 증가가 JVM 전체 적재·주문별 JDBC 왕복·하나의 장기 트랜잭션으로 번지는 것을 피하기 위한 구조입니다.

| 취소 대상 open order p95 | 실행 방식 | 트랜잭션 전략 |
|---:|---|---|
| 10,000건 이하 | PK keyset 1,000건 캡처 + 500건 반환 | 기본 bounded transaction과 `released_at` checkpoint 유지 |
| 10,001~100,000건 | 동일 cohort·동일 기본 chunk 유지 | 전체 시간은 늘어도 트랜잭션당 잠금·undo/redo 상한은 고정 |
| 100,000건 초과 | 기본은 동일 직렬 chunk, 검증 후 symbol 파티션 검토 | coordinator admission control, 병렬도는 여전히 1 |

chunk 전환 시에도 임의로 매번 열린 주문을 다시 검색하지 않습니다. 최초 frozen cohort의 `(close_cycle_id, order_id)`만 처리하고 다음 상태를 영속화해야 합니다.

- `captured_at`: 동결 cohort 포함 시각
- `released_at`: 예약금·예약수량 반환 완료 시각
- 캡처 watermark: snapshot에 커밋된 마지막 order PK
- 반환 watermark: `released_at is not null`인 주문 cohort
- cycle별 captured/updated/released count 대사

환경값도 무제한으로 키울 수 없습니다. 주문 캡처 청크는 1~10,000, 주문 취소·반환 청크는 1~5,000, 보유·계좌·대사 청크는 1~2,000 범위를 벗어나면 서버 기동을 거부합니다. 이 상한 안의 조정도 동일 MySQL 데이터·동일 동시성에서 commit p95/p99, undo/redo, row lock wait, freeze 완료시간을 비교한 뒤에만 허용합니다. 10만 건 초과에서 symbol 파티션을 검토하더라도 여러 symbol을 병렬 실행하는 것이 기본안은 아닙니다. 계좌가 여러 종목에 걸쳐 있어 병렬 취소가 동일 계좌 lock 경합을 다시 만들 수 있기 때문입니다.

신규 청크 조회 인덱스는 immutable EOD 스냅샷과 저빈도 현금흐름·기업행사 권리 원장에만 둡니다. 이미 immutable snapshot DDL을 적용한 운영 DB는 별도 `stock_eod_volume_indexes_alter.sql`로 계좌 정산·대사 cursor, 주문 반환 cursor, 주문 캡처 재시작 stream, 계좌별 cash-flow watermark, 계좌별 청약 상태 인덱스를 조건부 추가하고 기존 주문 스냅샷의 `source_order_status` cursor를 보정합니다. `stock_order`·`stock_execution`에는 EOD 전용 인덱스를 추가하지 않으므로 정규장 주문·체결 INSERT의 B-tree 쓰기 증폭은 없습니다. 보정 UPDATE와 ALTER는 백엔드·배치가 모두 종료된 것을 사용자가 확인한 뒤에만 실행합니다.

정규장 성능 보호를 위해 다음 운영 admission 규칙을 사용합니다. 아래 항목 중 시장 OPEN 차단, 단계 직렬화, readiness 30분 전 비필수 작업 신규 시작 금지는 현재 소스에 구현되어 있습니다. DB 전체 부하 신호에 따른 자동 연기는 아직 운영 승인 전 목표 정책입니다.

- `session_state=OPEN`인 동안 장기 보고서·캔들·전체 execution 집계 실행 금지
- close freeze가 `CLOSING`을 획득하면 신규 자동 주문·유동성 공급·만료 작업 신규 진입 중단
- freeze와 settlement가 동시에 실행되지 않음
- settlement와 overnight 대형 집계가 동시에 실행되지 않음
- **검증 대기:** DB 전체 active connection이 승인된 capacity의 70% 이상이거나 DB 전체 lock-wait p95가 10ms를 넘으면 비필수 야간 Step을 `DEFERRED`
- readiness 마감 30분 전에는 신규 비필수 대형 작업을 시작하지 않음

현재 batch 프로세스의 Hikari active 비율만으로는 백엔드와 다른 DB client의 부하를 볼 수 없고, 애플리케이션 내부 metric만으로는 MySQL 전체 lock-wait p95를 신뢰성 있게 계산할 수 없습니다. 따라서 batch pool gauge만 보는 가짜 admission class를 추가하지 않습니다. MySQL 8 공식 문서 기준 [`performance_schema.threads`](https://dev.mysql.com/doc/refman/8.0/en/performance-schema-threads-table.html)는 서버 thread별 현재 상태, [`data_lock_waits`](https://dev.mysql.com/doc/refman/8.0/en/performance-schema-data-lock-waits-table.html)는 현재 blocking edge, [`events_waits_current`](https://dev.mysql.com/doc/refman/8.0/en/performance-schema-wait-tables.html)는 thread별 현재 wait 하나를 보여주는 **순간 스냅샷**입니다. `data_lock_waits`에는 wait duration도 없으므로 이 표를 한 번 읽은 값을 과거 구간의 lock-wait p95라고 부를 수 없습니다. 진짜 p95 admission은 exporter 또는 bounded sampler가 동일 정의·주기·누락 정책으로 시계열을 먼저 축적해야 하며, instrumentation/consumer·SELECT 권한·조회비용·샘플 누락·DB 접근 실패 시 fail-open/fail-closed 정책까지 MySQL A/B에서 승인한 뒤 구현합니다. 그 전까지의 강제 안전장치는 열린 시장 거부, 전역 heavy-admission 1개, bounded chunk, 시간대 phase와 readiness cutoff입니다.

부하검증 결과는 cycle ID, build SHA, schema version, 데이터 규모와 함께 보존합니다. 단순 평균값이 아니라 p95/p99, 최대 lock wait, timeout, deadlock, 거래 TPS를 함께 비교해야 합니다.

### 11.9 관리자 EOD 조회 부하 예산

관리자 상태 화면을 열어 둔 것만으로 주문·체결 DB가 느려지면 안 됩니다. 이를 위해 장마감과 정산 단계가 `stock_post_close_cycle_metric` 한 행에 다음 수량을 미리 기록합니다.

- 취소 전 캡처 주문 수와 실제 취소 주문 수
- 정산 대상 계좌 수
- 계좌·보유·가격·미체결 요약 스냅샷 수
- 대사 불일치 수
- 정산 완료·누락 계좌 수

관리자 API 한 번의 조회 예산은 거래량과 무관한 다음 범위로 제한합니다.

1. singleton `stock_market_business_state` 한 행
2. 활성 종목 설정의 작은 집계 한 번
3. full-market cycle의 oldest-incomplete 또는 latest-completed bounded index 조회
4. cycle PK의 metric 한 행
5. 해당 cycle의 최신 attempt와 signal 각 한 행

다음 쿼리는 관리자 폴링 경로에서 금지합니다.

- `stock_order`·`stock_execution`의 `COUNT`, `SUM`, `GROUP BY`
- 스냅샷 테이블 전체를 다시 세는 ad-hoc 집계
- 거래일 전체 현금흐름·기업행사 원장 재집계
- `DATE()`·`TIME()` 함수가 인덱스 열을 감싸는 기간 검색

현재 종목 수가 작아 활성 종목 수는 설정 테이블에서 bounded 집계합니다. 종목 수가 크게 늘어 이 조회가 의미 있는 비용이 되면 cycle/control metric에 함께 저장하고 폴링 경로에서는 PK 조회만 남깁니다.

프론트 조회 정책은 다음과 같습니다.

- `/admin/system/eod`가 실제로 열린 동안만 활성화
- 기본 15초 주기, stale time 10초
- 화면을 벗어나거나 탭이 백그라운드이면 주기 조회 중지
- 10초 미만 주기는 동일 주문·체결 부하에서 DB CPU, lock wait, 주문 API p95를 측정하기 전에는 허용하지 않음

목표는 관리자 한 명이든 여러 명이든 조회 비용이 거래 주문·체결 건수에 비례하지 않게 하는 것입니다. 필요하면 다중 관리자 중복 조회는 짧은 서버 캐시나 single-flight로 추가 흡수하되, 원장 집계를 다시 도입하지 않습니다.

### 11.10 EOD 스냅샷 증가량과 보존 정책

정규장 hot table 부하를 줄였더라도 EOD 상세 스냅샷을 무기한 같은 OLTP DB에 쌓으면 장기적으로 buffer pool과 백업·복구 시간을 압박합니다. 보존 단위는 행 생성 시각이 아니라 `close_cycle_id`/거래일 cohort로 고정합니다.

- `stock_close_open_order_snapshot`: 단기 감사용 상세. 요약 대사와 이의제기 보존기간이 지난 cycle은 archive 후 삭제 가능
- `stock_close_open_order_summary`, `stock_post_close_cycle_metric`: 장기 운영 추세용 소형 데이터로 유지
- 계좌·가격·보유·포트폴리오 스냅샷: 수익률 재현·회계 감사 기간에 맞춰 보존하고, 장기분은 별도 분석 저장소로 이동
- phase attempt와 Spring Batch 성공 메타데이터: 재시작 가능 기간과 운영 감사 기간을 분리해 archive/purge

정리 작업은 02:00 이후 필수 overnight phase가 끝난 뒤 cycle PK 기준 bounded chunk로만 실행합니다. 한 번에 대량 cascade delete를 실행하거나 정규장에 purge를 허용하지 않습니다. 기본 삭제 수량·보존일은 운영 정책과 실데이터 증가율을 측정한 뒤 확정하며, 정책이 확정되기 전에는 자동 삭제를 활성화하지 않습니다.

---

## 12. Signal 큐 재설계

현재 reader는 오래된 PENDING 한 건을 찾은 뒤 상태를 바꾸고, defer되면 processor가 반복을 중단합니다.

목표 claim SQL:

```sql
SELECT id
  FROM stock_batch_job_signal
 WHERE status IN ('PENDING', 'DEFERRED')
   AND next_attempt_at <= CURRENT_TIMESTAMP
 ORDER BY next_attempt_at, id
 LIMIT ?
 FOR UPDATE SKIP LOCKED
```

그 후 같은 트랜잭션에서:

- `PROCESSING`
- `claim_token`
- `lease_until`
- `attempt_count + 1`

을 기록합니다.

정규장 빈 큐의 5초 polling이 업무 DB commit 부하로 바뀌지 않도록 claim 앞에는 동일 claim 인덱스를 쓰는 비잠금 존재 조회를 둡니다. 대상이 없으면 `FOR UPDATE` 트랜잭션과 attempt UPDATE를 시작하지 않습니다. 한 poll은 기본 20건, 최대 100건만 연속 claim하며 최대 시도에 도달한 만료 lease도 기본 60초마다 정확한 PK cohort 100건만 `FOR UPDATE SKIP LOCKED`로 전환합니다. 따라서 signal backlog가 커져도 한 maintenance poll의 잠금·후속 Job 제출량이 무제한으로 커지지 않습니다. 신호가 실제 존재할 때 한 번의 비잠금 사전 조회가 추가되는 대신, 대부분의 빈 poll에서 두 개의 빈 쓰기 트랜잭션을 제거하는 선택입니다.

claim된 신호가 동기식 대상 Job을 실행하는 동안에는 기본 30초마다 `where id = ? and claim_token = ? and status = 'PROCESSING'`인 정확한 한 행의 `lease_until`만 연장합니다. 기본 lease 180초보다 heartbeat 간격이 짧지 않으면 서버 기동을 거부하고, 첫 연장 시점에 소유권이 이미 사라졌으면 업무 Job을 시작하지 않습니다. 단발성 DB·네트워크 예외는 남은 lease 여유 안에서 다음 heartbeat가 다시 갱신하도록 하며, 한 번의 일시 오류가 이후 갱신 전체를 중단하게 하지 않습니다. 종료 상태를 쓰기 전 heartbeat를 취소하고 진행 중 갱신과 직렬화한 뒤 같은 PK·claim token을 최종 갱신하여 소유권을 다시 증명합니다. 최종 소유권이 사라졌으면 stale token으로 `COMPLETED`/`FAILED`/`DEFERRED`를 덮어쓰지 않고 현재 poll만 종료합니다. 따라서 완료 뒤 lease가 되살아나지 않고, 소유권 상실을 기록하려다 두 번째 예외로 signal poll 전체가 중단되지 않습니다. 이 제어행 UPDATE는 주문·체결 hot table, 계좌, 보유, 가격을 전혀 읽지 않으며 신호를 실제 처리하는 동안에만 발생합니다.

실패 분류:

- 재시도 가능: lock timeout, deadlock, 일시적 연결 종료
- 연기: phase 미도달, 시장 세션 부적합
- 영구 실패: 잘못된 거래일·epoch, 없는 종목, 정책 위반
- 최대 재시도 초과: `DEAD_LETTER`

정규장 수동 전체 장마감은 다음 두 계층에서 모두 차단합니다.

- API enqueue: `BatchJobSignalService`
- 배치 실행: `StockBatchJobLauncher`

강제 마감은 현재 구현하지 않습니다. 일반 전체 마감은 API enqueue와 batch launcher 양쪽에서 장후 세션만 허용하며, 내부 `force` boolean이나 일반 관리자 버튼으로 우회하지 않습니다. 향후 운영상 반드시 필요하다는 별도 승인이 있을 때만 다음 요건을 모두 갖춘 별도 API·별도 신호 유형으로 추가합니다.

- 고권한
- 사유 필수
- 2단계 확인
- 감사 원장
- 먼저 fence close
- in-flight drain
- 그 후 rollover

---

## 13. 관리자 화면 계획

현재 `AdminBatchRuntimeControls.tsx`와 `AdminBatchRuntimeCards.tsx`는 개별 배치 ON/OFF 중심입니다.

EOD 전용 패널을 별도로 두는 편이 맞습니다.

표시 항목:

- 원시 시뮬레이션 시각
- 활성 거래일
- 다음 준비 거래일
- 현재 cycle phase
- phase 시작·완료·경과시간
- 다음 실행 가능 시각
- close run/attempt ID
- 장마감 대상 종목·계좌 수
- 취소 주문·반환 금액
- 스냅샷 생성·누락 수
- 정산 완료·실패 계좌 수
- 기업행사 대기·실패 수
- readiness 검사 결과
- build SHA·schema version
- 마지막 오류와 다음 재시도 시각

버튼:

- 정상 phase 수동 실행은 제공하지 않음. 실행 가능 시각이 되면 10초 coordinator가 자동 선점하므로 별도 신호는 정책 중복·중복 실행 표면만 늘림
- 재시도: 실패한 현재 phase만
- 강제 마감: 현재 제공하지 않음. 향후 승인 시에만 별도 위험 영역으로 추가
- 무조건 “지금 실행” 버튼은 제거
- 아직 실행 시각이 아니면 `00:10 실행 예정`으로 표시

보고서·차트 집계는 관리자 실시간 운영 패널과 분리합니다.

- 종목 보고서: 완료된 close cycle 스냅샷
- 실시간 호가·유동성: 시장 운영 패널
- 두 데이터의 기준 시각을 명확히 표시

### 13.1 현재 구현된 EOD 운영 화면

`/admin/system/eod`에는 다음 소스 구현이 반영되어 있습니다.

- 원시 시뮬레이션 시각·활성 거래일·다음 준비 거래일
- 주문 진입 gate와 현재 시장 상태
- 정상 운영에 노출하는 11개 phase를 가로 스크롤 없이 감싸는 반응형 진행 표시. 내부 절차인 `ORDER_ENTRY_CLOSED`·`EXECUTION_DRAINED`와 cycle 전 상태인 `OPEN`은 별도 칸으로 중복 표시하지 않음
- close run·실행 가능 시각·최근 attempt·build/schema 정보
- cycle과 최근 phase attempt의 시작·완료·경과시간. 진행 중 경과시간은 별도 브라우저 timer가 아니라 15초 overview 응답의 `generatedAt`을 기준으로 계산해 백그라운드 wake-up을 만들지 않음
- 실패·연기된 cycle의 서버 기준 다음 재시도 가능 시각
- 취소 주문, 계좌·보유·가격·미체결 요약, 정산 완료·누락, 대사 불일치 수
- 정산, 거래일, 시장 CLOSED, fence `PREPARING`, 가격 snapshot, 기업행사 현금/수량 변환, regime, Redis profile queue, build/schema의 10개 readiness 결과와 검사 시각
- 최근 signal과 마지막 오류
- 수동 새로고침, loading·empty·error 상태
- 가장 오래된 전체시장 `FAILED` cycle의 현재 phase 재시도 버튼

넓은 표와 내부 가로 스크롤은 사용하지 않습니다. 작은 화면에서는 정보 블록이 자연스럽게 한 열로 내려가며, 바깥 페이지 스크롤 하나만 사용합니다.

화면의 데이터는 `GET /api/stock/v1/markets/batch-jobs/eod/overview`에서 읽습니다. 이 API는 `stock_post_close_cycle_metric`, `stock_post_close_readiness_check` 등 제어·요약 테이블만 사용하며 주문·체결 원장을 조회하지 않는 것을 계약으로 봅니다. 소스 구현과 lint/typecheck/build 검증은 완료했지만 신규 DDL의 운영 반영과 실제 로그인 세션 브라우저 검증은 서버 재기동 이후 별도로 수행합니다.

계획의 표시 항목과 실패 phase 재시도 명령은 소스에 구현됐습니다. 원시 시뮬레이션 시각은 기존 singleton `stock_simulation_clock`을 business-state 조회에 함께 조인해 제공하고, cycle·최근 phase attempt의 시작·완료·경과시간은 이미 저장된 제어행과 overview `generatedAt`만으로 표시합니다. 추가 polling이나 클라이언트 초단위 timer는 만들지 않습니다. 취소 뒤 반환된 매수 예약금과 매도 예약수량은 동결 완료 시 `stock_close_open_order_summary`의 종목별 요약행을 한 번 합산해 `stock_post_close_cycle_metric`에 고정하고, 화면은 이 cycle PK 요약행만 읽도록 구현했습니다. 정규장 주문·체결 원장과 그 인덱스에는 변경이 없고 관리자 polling 때 합산도 반복하지 않습니다.

05:30 readiness는 `REQUIRES_NEW` 진단 트랜잭션에서 고정 10개 검사를 수행하고 성공·실패 결과를 `stock_post_close_readiness_check`의 `(close_cycle_id, check_code)` 행으로 교체합니다. readiness Step이 실패해도 `noRollbackFor`로 해당 진단행을 보존하므로 화면은 실패 원인을 다시 계산하지 않습니다. 기업행사 현금·PRE_OPEN 변환은 각 due-action 미완료 수를 readiness 실패 건수로 저장하며, 프로필 큐는 고정 profile enum 범위의 DB schedule projection과 Redis/JVM snapshot만 비교합니다. 백엔드 overview는 cycle PK와 SQL `limit 10`으로 최대 10행만 읽을 뿐 `stock_order`·`stock_execution`·현금흐름·기업행사 원장을 15초마다 재집계하지 않습니다. 시장 상태는 주문장과 가상가격의 enabled 설정을 작은 `UNION ALL`로 함께 세어 어느 한쪽이라도 OPEN이면 주문 접수 OPEN으로 표시합니다. 정상 phase는 실행 가능 시각 이후 10초 coordinator가 자동 선점하므로 별도 수동 실행 명령을 추가하지 않습니다. 수동 명령을 하나 더 두면 동일 eligibility·lease 정책을 API와 배치에 중복하거나 자동 선점과 경쟁하게 되어, 기능 가치보다 오류 표면이 커집니다. 따라서 현재 화면 판정은 **계획 표시·실패 phase 재시도 소스 구현, live 브라우저 검증 미완료**입니다.

`POST /api/stock/v1/markets/batch-jobs/eod/cycles/{cycleId}/retry`는 ADMIN 인증 주체만 호출할 수 있습니다. 대상 cycle을 PK `FOR UPDATE`로 잠근 뒤 전체시장 `ALL`, `FAILED`, owner 없음, 실행 중 attempt 없음, 시장 CLOSED, 가장 오래된 미완료 cycle임을 같은 트랜잭션에서 검증하고 `PENDING`으로 되돌려 `next_retry_at`만 해제합니다. 마지막 오류와 과거 attempt는 보존하며 다음 claim이 새 attempt를 기록합니다. `DEFERRED`는 실행 시각·정책 대기를 뜻하므로 수동으로 우회하지 않습니다. 이 명령은 제어 테이블만 사용하며 Job을 직접 호출하지 않아, 완료된 Step 재실행이나 정규장 주문·체결 부하를 만들지 않습니다.

강제 마감 명령은 여전히 제공하지 않습니다. 거래일·epoch·고권한·사유·2단계 확인·감사 원장을 갖춘 별도 workflow가 승인되기 전에는 일반 retry API나 UI에 `force` 옵션을 섞지 않습니다.

폴링 조회의 인덱스도 거래 원장과 분리합니다. scheduler의 가장 오래된 미완료 cycle 정렬은 `idx_stock_post_close_cycle_scope_date_status`, 상태 중심 관리자 조회는 `idx_stock_post_close_cycle_scope_status_date`, 최근 cycle signal은 `idx_stock_batch_job_signal_cycle_id` 후보를 사용합니다. 실제 선택은 운영 MySQL `EXPLAIN ANALYZE`로 확인하되 두 테이블은 거래마다 쓰는 원장이 아니라 cycle/수동 신호 제어 테이블이므로 주문·체결 INSERT 비용을 증가시키지 않습니다. 필수 제어 인덱스가 빠진 스키마에서는 readiness 검증이 재기동을 차단합니다.

Coordinator는 시장이 열린 뒤에도 `READY_TO_OPEN`인 직전 cycle 하나는 `COMPLETED`로 닫을 수 있어야 합니다. 시장 OPEN 여부만 먼저 보고 즉시 반환하면 `OrderBookMarketSessionStateService`가 06시에 시장을 연 다음 cycle이 영구히 `READY_TO_OPEN`에 남습니다. 현재 구현은 bounded cycle 인덱스 조회 후 `READY_TO_OPEN`만 허용하고, 다른 야간 phase는 열린 시장에서 계속 거부합니다. 정규장에 추가되는 것은 10초마다 작은 cycle 제어 테이블 한 번뿐이며 `stock_order`·`stock_execution`은 조회하지 않습니다.

---

## 14. 파일별 리팩터링 범위

### `stock-batch-service`

- `PortfolioSettlementScheduler.java`
  - 18시 시장 차단·원장 동결·지연 정산 prefix 유지
  - 가장 오래된 미정산 cycle과 active business date를 먼저 복구
  - `currentDate.minusDays(1)`은 이전 날짜 하나만 권위 있게 고르는 로직이 아니라, cycle·active-state 복구 대상이 없을 때의 bounded PRE_OPEN fallback으로만 사용

- `PostCloseCoordinatorScheduler.java`
  - `PORTFOLIO_SETTLED` 이후 00시·04:30·05:30·06시 suffix를 거래일 phase로 순차 실행
  - 열린 시장에서는 `READY_TO_OPEN → COMPLETED` 확인 외의 무거운 phase를 실행하지 않음

- `OrderBookMarketSessionStateService.java`
  - DB session fence와 active business date 기반으로 전환

- `MarketCloseRolloverService.java`
  - cycle/attempt 기반 멱등 실행
  - Redis lock을 correctness 수단에서 제외

- `MarketCloseRolloverWriter.java`
  - 취소 전/후 지표 분리
  - 계좌·가격 스냅샷
  - 장마감 hot-path 집계 제거

- `MarketClosePostProcessingCompletionService.java`
  - 현재 계좌 수 비교 제거
  - frozen cohort `NOT EXISTS` 검증

- 체결·자동 주문·상장주관사 주문 경로
  - 최종 트랜잭션 session fence
  - 공통 lock-order helper

- 기업행사 scheduler/service/readers
  - 현금 단계와 PRE_OPEN 변환 단계 분리
  - 처리 원장 적용

- signal reader/processor/writer
  - lease, retry, backoff, `SKIP LOCKED`

- `ExecutionAccountDaySummaryAccumulator`
  - durable/rebuildable 구조로 전환

- `application.yml`, README
  - 장마감 폴링 10초/5초 불일치 제거
  - typed configuration + 계약 테스트

### `stock-back-service`

- `TradingService.java`
  - 신규 주문·정정 모두 최종 transaction fence

- `BatchJobSignalService`
  - 거래일·epoch·cycle 포함
  - 일반 마감은 장후 세션만 허용
  - 강제 마감 API는 의도적으로 미노출; 향후 승인 시 일반 신호와 완전히 분리

- EOD 조회 API
  - cycle overview
  - phase attempts
  - readiness
  - snapshot reconciliation
  - signal state
  - 가장 오래된 전체시장 `FAILED` cycle 현재 phase의 bounded retry command

- DB DDL
  - cycle/fence/attempt/snapshot/action ledger/signal 확장
  - startup schema readiness

### `stock-front-service`

- EOD 운영 패널 신규 구성
- queued count의 0건 표현 제거
- phase 상태와 실행 가능 시각 표시
- 가장 오래된 전체시장 `FAILED` cycle의 현재 phase 재시도 버튼
- 원시 시각·활성 거래일 분리 표시
- 강제 마감 위험 모달은 강제 workflow와 함께 보류
- report snapshot 기준일 표시 강화

### DDL 계약

현재 프로젝트 규칙대로 다음을 함께 갱신해야 합니다.

- canonical `stock_all.sql`
- 필요한 ALTER 파일
- batch H2 DDL
- clear/reset 스크립트
- 백엔드·배치 DDL 계약 테스트
- `USE STOCK_SERVICE;`
- 인덱스·제약 이름 정합성

---

## 15. 적용 순서

### 0단계 — 배포 정합성부터 고정

- build SHA, 애플리케이션 버전, schema version 노출
- 시작 시 필수 테이블·컬럼 검사
- 과거 보유 지표 `NULL` 재발 여부와 EOD 귀속·검증 컬럼의 실제 반영 확인
- 설정값 코드/YAML/README 통일
- cycle ID를 EOD Job 실행 scope의 로그 MDC에 추가

완료 조건: 실행 프로세스가 어느 소스·스키마를 사용하는지 즉시 증명 가능.

상태: **소스 보강, 새 EOD 산출물 운영 대조 미완료**. build/schema version, 시작 readiness와 EOD 실행 scope의 cycle MDC를 구현했다. 로컬 Gradle 빌드는 HEAD SHA에 워킹트리 변경이 있으면 `-dirty`를 붙이고, CI가 `BUILD_SHA`를 주입하면 그 content-addressed 값을 우선한다. 최신 10개 정산일의 기존 보유지표는 모두 non-null이지만, 신규 EOD 클래스·귀속 컬럼을 포함한 실행 JAR과 운영 DB 대조는 남았다. 일반 주문·체결 경로와 독립 비동기 스레드에 MDC를 강제로 전파하지 않아 정규장 hot path 비용과 문맥 누수를 피한다.

### 1단계 — 장마감 경계 방어

- session fence 테이블
- 사용자 주문·정정 적용
- 자동 주문·상장주관사 주문 적용
- 체결 적용
- 정규장 수동 마감 이중 차단
- 경계 동시성 MySQL 테스트

완료 조건: cutoff 이후 신규 승인 0건.

상태: **소스 구현, MySQL 경계 A/B 미완료**.

### 2단계 — cycle과 attempt

- `stock_post_close_cycle`
- `stock_post_close_phase_attempt`
- 기존 close run 연결
- 논리적 유일성
- coordinator lease/CAS

완료 조건: 다중 서버가 경쟁해도 한 노드만 phase 선점.

상태: **소스·H2 계약 구현, 다중 프로세스 MySQL 검증 미완료**.

### 3단계 — 불변 장마감 스냅샷

- 계좌 cohort
- 현금·예약금
- 전 종목 가격
- 보유·예약 수량
- 취소 전 주문 지표
- 대사 검증

기존 스냅샷과 새 스냅샷을 dual-write합니다.

상태: **소스 구현**. 새 frozen 입력과 기존 일일 스냅샷 기록은 함께 존재하지만 운영 ALTER 적용과 실데이터 대사는 아직 완료하지 않았다.

### 4단계 — 정산 shadow 실행

- 기존 실시간 정산 유지
- 새 snapshot 정산을 별도 테이블 또는 shadow 컬럼에 계산
- 계좌별 total asset·수익률·보유지표 비교
- 차이 사유 보고서 생성

차이가 0이거나 정책적으로 설명 가능한 수준이 되기 전에는 전환하지 않습니다.

상태: **bounded 운영 진단 소스 구현, 운영 실행 미완료**. 현재 소스는 snapshot reader로 전환됐고, 저장 결과가 frozen 입력 산식과 정확히 일치하는지는 완료 단계에서 전 필드를 다시 계산해 검증한다. 이 자체 대사는 정상 구현의 내부 일관성을 증명하지만, 구형 실시간 reader 결과와 같은 close cycle을 비교하는 rollout shadow를 대체하지 않는다.

운영 ALTER 후 첫 cycle이 정확히 `PORTFOLIO_SETTLED`이고 다음 00시 현금 phase가 아직 시작되지 않았을 때 다음 읽기 전용 진단을 사용한다.

```sql
SET @stock_eod_shadow_cycle_id = <첫 frozen 정산 cycle id>;
SET @stock_eod_shadow_after_account_id = 0;
SOURCE stock-batch-service/src/main/resources/db/diagnostics/stock_eod_settlement_shadow_mysql.sql;
```

첫 결과 집합의 `guard_status=ACCEPTED`를 확인한 뒤, 비교 결과의 `next_after_account_id`를 다음 cursor로 넣어 빈 페이지가 나올 때까지 반복한다. 각 실행은 `START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY` 안에서 frozen target 계좌 PK 200개만 비교하며, 현재 cash-flow·holding·open-order·subscription 조회는 계좌 선두 인덱스를 강제한다. `stock_execution`은 읽지 않고 어떤 테이블에도 쓰지 않는다. `page_mismatch_count`로 페이지 전체 통과 여부를 먼저 확인하고, `shadow_status=MISMATCHED` 계좌는 현금 반환 구성 차이, 외부 순입금, 평가가격, 보유·예약수량, 저장된 total asset·수익률·계산 버전을 같은 행에서 확인한다. 시장이 열렸거나 cycle phase가 바뀌었거나 `PORTFOLIO_SETTLED` phase attempt가 한 번이라도 생겼으면 guard는 명시적 `REJECTED` 사유를 반환하고 비교 CTE는 0건으로 닫힌다. 따라서 00시 현금 작업이 일부 커밋된 뒤 실패·연기된 cycle과 mysql client가 오류 뒤 문장을 계속 실행하는 설정에서도 변형된 live row를 정상 shadow 결과로 오인할 수 없다.

실 DB 입력과 정확한 실행시각이 없는 상태에서 영구 shadow 테이블이나 정규장 이중 쓰기는 추가하지 않는다. 따라서 소스 도구가 존재해도 첫 운영 cycle의 모든 페이지가 `MATCHED`이거나 정책적으로 설명·승인되기 전에는 shadow 게이트를 통과한 것으로 판정하지 않는다.

### 5단계 — 정산 cut-over

- snapshot reader로 전환
- frozen cohort 완료 판정
- `input_hash` 검증
- 기존 실시간 reader 비활성화

상태: **소스 cut-over 완료, 운영 승인 미완료**. 4단계 shadow 결과와 운영 MySQL 검증 없이 전체 rollout이 완료됐다고 판정하지 않는다.

### 6단계 — 시간대 coordinator 적용

- 18시 freeze
- 18:10 정산
- 00시 overnight
- 04:30 preopen
- 05:30 readiness
- 06시 open

기존 독립 scheduler는 coordinator phase guard 뒤에 두고, 안정화 후 제거합니다.

상태: **소스 구현**. 지연 민감 prefix는 `PortfolioSettlementScheduler`가 시장 차단·동결·정산까지 담당하고, `PostCloseCoordinatorScheduler`는 `PORTFOLIO_SETTLED` 이후 시간대 suffix를 담당한다. 두 scheduler는 단일 post-close executor에서 직렬 실행된다. 현금흐름·기업행사·시세·빈 보유·regime·프로필 큐의 호환 scheduler만 coordinator 활성 시 DB runtime-control·JobRepository 접근 전에 반환하며, 운영 안정화 이후 이 호환 경로들의 제거 여부를 다시 판단한다.

### 7단계 — 기업행사·현금흐름 분리

- action processing ledger
- 최종일 청약 복구
- 배당→청약 정책
- 자동 월급 overnight 제한
- 가격·수량 변환 PRE_OPEN 이동

상태: **소스 구현, 운영 cycle 검증 대기**. 기업행사 처리 원장, 마지막 청약일 복구, 배당→청약 순서, 야간/PRE_OPEN 분리를 구현했다. 자동 월급은 야간 거래일당 1회 due-sweep와 cursor 멱등성을 사용하고 신규·수정 설정을 `DAY`·`MONTH`·`YEAR`로 제한해 정규장 부하와 오해 가능한 sub-day cadence를 함께 차단했다.

### 8단계 — Signal·집계·메타데이터 최적화

- lease/retry/backoff/dead letter
- 계좌 summary durable화
- no-op Job 실행 억제
- Batch metadata 보존정책
- 대형 쿼리 범위 조건 전환

상태: **소스 구현, 운영 활성화 미완료**. signal·durable summary·no-op 억제·범위 쿼리와 완료 metadata compact archive/allow-list purge 실행기를 구현했다. 기능과 purge는 기본 비활성이며 metadata 전용 DDL, 보존 현실 일수, 삭제 허용 Job 목록과 동일 호스트 I/O A/B가 승인되기 전에는 운영에서 켜지 않는다.

### 9단계 — 관리자 UI와 운영 전환

- cycle/phase 패널
- readiness
- deferred signal 표현
- 위험 작업 분리
- 운영 runbook 작성

상태: **부분 완료**. EOD 조회 화면, 실패한 현재 phase의 bounded 재시도 명령·버튼, 본 문서의 운영 절차는 존재한다. 강제 마감 workflow는 의도적으로 미구현이고 로그인 세션 live 브라우저 검증은 남았다.

DB ALTER는 사용자가 백엔드·배치 서버를 종료한 뒤 적용하고, 에이전트가 자동으로 서버를 종료하지 않는 흐름을 유지합니다.

### 운영 DB ALTER 적용 순서와 부하 보호

서버 종료를 확인한 유지보수 창에서 다음 11개만 순서대로 적용한다. `*_alter.sql` 전체를 다시 일괄 실행하지 않는다. 과거 주문·체결 인덱스 ALTER까지 다시 실행하면 현재 추정 주문 151만/체결 103만 이상 테이블의 DDL 여부 확인과 인덱스 작업 부하를 불필요하게 재발생시킬 수 있다.

아래 11개는 `STOCK_SERVICE` business schema용이다. metadata 보존을 운영 승인할 때는 백엔드·배치 종료와 실행 중 native Job 0건을 별도로 확인한 뒤 `stock-batch-service/src/main/resources/db/schema/batch-metadata-retention-mysql.sql`을 `STOCK_BATCH_METADATA`에 적용한다. 이 파일은 compact archive 테이블과 `BATCH_JOB_EXECUTION` 후보 인덱스만 만들며 business ALTER 11개에 포함하지 않는다. 적용 전후 `stock_order`·`stock_execution` 정의는 바뀌지 않아야 하고, 적용 직후에도 `STOCK_BATCH_METADATA_RETENTION_ENABLED=false`, `PURGE_ENABLED=false`를 유지한다.

1. `stock_eod_session_fence_alter.sql`
2. `stock_eod_cycle_alter.sql`
3. `stock_eod_immutable_snapshot_alter.sql`
4. `stock_eod_report_participant_snapshot_alter.sql`
5. `stock_batch_job_signal_lease_alter.sql`
6. `stock_corporate_action_processing_alter.sql`
7. `stock_corporate_action_chunking_alter.sql`
8. `stock_execution_daily_account_last_executed_at_alter.sql`
9. `stock_execution_profit_summary_alter.sql`
10. `stock_eod_volume_indexes_alter.sql`
11. `stock_auto_participant_cash_flow_run_alter.sql`

각 파일은 `CREATE TABLE IF NOT EXISTS` 또는 `information_schema` 기반 조건부 DDL을 사용한다. 2번 cycle ALTER는 이미 cycle 테이블이 생성된 DB에도 `next_retry_at`을 조건부로 추가하고, 3번 immutable snapshot, 4번 참가자 분류 snapshot, 5번 signal lease는 파일 중간에 연결이 끊겨도 재실행 시 이미 존재하는 컬럼·인덱스·CHECK를 건너뛰고 남은 보정부터 진행한다. MySQL이 지원하지 않는 `ADD COLUMN IF NOT EXISTS` 문법은 사용하지 않는다. 단, 조건부라는 이유로 정규장 중 재실행하지 않으며 아래 사전/사후 검증과 유지보수 창 원칙은 그대로 적용한다.

3번 immutable snapshot ALTER에는 계좌별 `holding_market_value`, `holding_quantity`, `reserved_sell_quantity`, `holding_position_count`도 포함한다. 이 네 값은 정산 때 편의를 위해 실시간 보유를 다시 읽는 캐시가 아니라, 같은 close cycle의 `stock_holding_snapshot`에서 장마감 트랜잭션 안에 한 번 계산한 불변 입력이다. 시작 readiness가 네 컬럼을 모두 검사하므로 구버전 스키마로 새 정산 reader를 실행하지 않는다.

4번은 과거 immutable snapshot 배포본에 `participant_category`가 없을 때만 저빈도 `stock_close_account_snapshot`에 열과 CHECK를 추가합니다. 기존 행은 frozen `user_key`와 `stock_auto_participant(user_key)` PK 조회로만 보정하고 `stock_order`·`stock_execution`은 읽거나 변경하지 않습니다. 신규 cycle은 장마감 스냅샷과 같은 쓰기에서 값을 확정하므로 야간 보고서가 변경 가능한 프로필 설정을 다시 조인하지 않습니다.

8번은 대형 체결 원장 `stock_execution`을 변경하지 않고 `stock_execution_daily_account_snapshot` 요약 테이블에 `last_executed_at` 한 열만 추가합니다. 야간 보고서의 계좌·종목별 최종 체결 시각을 보존하는 보조 스키마이며, 정규장 체결 INSERT의 인덱스 쓰기 수에는 영향을 주지 않습니다.

8번은 [MySQL 8 공식 `ALTER TABLE` 문법](https://dev.mysql.com/doc/refman/8.0/en/alter-table.html)에 없는 `ADD COLUMN IF NOT EXISTS`를 사용하지 않습니다. `information_schema.columns`로 열 존재 여부를 확인해 필요한 경우에만 동적 `ALTER TABLE ... ADD COLUMN`을 실행하므로 유지보수 창 재확인 시 이미 적용된 열 때문에 실패하지 않습니다.

9번은 저빈도 `stock_execution_account_day_summary`에 매수/매도 gross·net, 수수료, 세금, 실현손익 열을 조건부로 추가합니다. 누락 열이 하나라도 있었던 최초 적용에서만 현재 추정 103만 이상 체결 원장을 한 번 `GROUP BY DATE(executed_at), account_id`로 읽어 과거 요약을 보정하고, 재적용은 구조 검사만 하는 no-op입니다. 이 전체 이력 backfill은 반드시 백엔드·배치가 모두 종료된 유지보수 창에서 실행하며, `stock_execution` 자체의 열·인덱스·데이터는 변경하지 않습니다. 운영 재개 후에는 장중 after-commit 메모리 병합과 야간 한 거래일 인덱스 범위 대사만 사용합니다.

10번은 이미 3번을 적용한 DB의 immutable snapshot cursor와 저빈도 현금흐름·기업행사 조회 인덱스를 보강하는 증분 ALTER입니다. 기존 `stock_close_open_order_snapshot` 행은 주문 PK 조인으로 `source_order_status`를 한 번 보정한 뒤 `NOT NULL`과 stream 인덱스를 적용합니다. 추가 인덱스는 `stock_account_cash_flow(account_id, id)`, `stock_corporate_action_entitlement(account_id, status)`, `stock_close_account_snapshot(close_cycle_id, reconciliation_status, account_id)`이며, 계좌 청크가 같은 범위를 반복 스캔하는 비용을 줄입니다. 이 보정과 ALTER는 서비스가 종료된 유지보수 창에서만 수행하고, `stock_order`·`stock_execution`에는 열이나 인덱스를 추가하지 않습니다.

11번은 자동 월급·정기 입금 실행당 1행의 재시작 cursor 테이블만 생성합니다. 계좌 청크 수와 무관하게 제어행은 실행당 하나이며, 기본키 point lock과 완료시각 보존용 소형 인덱스만 사용합니다. 대형 주문·체결 테이블 또는 기존 현금흐름 테이블에는 열·인덱스·트리거를 추가하지 않습니다.

5번 signal ALTER의 다중 테이블 UPDATE 별칭은 `job_signal`을 사용합니다. MySQL 8에서 [`SIGNAL`은 예약어](https://dev.mysql.com/doc/refman/8.0/en/keywords.html)이므로 `signal`을 무인용 별칭으로 사용하지 않으며, 양쪽 DDL 계약 테스트가 이 문법 회귀를 차단합니다.

첫 번째 ALTER는 `base_simulation_date`를 현재 거래일로 그대로 복사하지 않는다. 서버가 정상 종료됐다면 `accumulated_real_seconds`, 비정상 종료되어 `running=true`가 남았다면 `last_started_at`부터 `last_heartbeat_at`까지만 더해 원시 시뮬레이션 날짜를 계산한다. 따라서 유지보수에 걸린 현실 시간은 시뮬레이션에 추가되지 않고, 장기간 가동된 DB가 기준일로 되감기지도 않는다. PRE_OPEN이면 활성 거래일은 계산된 원시 날짜의 전일로 초기화하되 기준일보다 앞서지 않게 한다.

운영 개장시각이 기본 `06:00`과 다르면 같은 MySQL 세션에서 첫 ALTER를 source하기 전에 다음 값을 설정해야 한다.

```sql
SET @stock_market_open_time = TIME('07:00:00');
```

이 초기화 쿼리는 `stock_simulation_clock` 한 행과 시장 설정의 종목 행만 읽으며 `stock_order`·`stock_execution`을 조회하거나 변경하지 않는다. 첫 기동 시 fence는 `CLOSED`로 시작하므로 배치가 현재 phase와 readiness를 확인하기 전에는 주문·체결이 열리지 않는다.

적용 전·후에는 다음을 기록한다.

- `information_schema.tables/columns/statistics` 기준 스키마 사진
- 각 ALTER 실행 시작·종료·오류·warning
- 테이블 행 수와 인덱스 크기
- DB connection, row lock wait, buffer-pool dirty page, redo 증가량
- back/batch mirrored DDL hash

신규 스키마 readiness validator가 필수 열·인덱스를 통과하기 전에는 백엔드·배치를 재기동하지 않는다. 적용 후에는 백엔드 한 개 인스턴스의 readiness를 먼저 확인하고, 배치를 한 개만 기동해 coordinator lease·cycle 상태를 검증한 뒤 정상 재개한다.

---

## 16. 필수 검증 시나리오

H2만으로는 부족합니다. MySQL 8 Testcontainers 테스트를 추가해야 합니다.

격리 MySQL 검증은 일반 `test`/`check`에서 제외하고 `./gradlew :stock-batch-service:mysqlTest`로 실행합니다. 이는 평소 빌드와 개발 서버가 Docker 기동·이미지 pull·컨테이너 I/O를 부담하지 않게 하면서도 CI/검증 환경에서는 MySQL 8 잠금 의미를 확인하기 위한 분리입니다. 두 suite는 `@Testcontainers`의 fail-closed 기본값을 사용하여 Docker가 없으면 컨테이너 초기화에서 명시적으로 실패합니다. 일반 테스트의 `StockMysqlVerificationGateTest`가 두 suite 모두 `disabledWithoutDocker=false`인지 고정하므로 전체 skip 뒤 `BUILD SUCCESSFUL`이 되는 회귀를 차단합니다. 승인 증거에는 결과 XML의 `tests`, `skipped`, `failures`, `errors`를 함께 기록하며 skipped가 1건이라도 있으면 실제 MySQL 동시성 통과가 아닙니다.

2026-07-16 현재 검증 기준 의존성은 Java 21, Spring Boot 4.0.2, Spring Batch 6.0.2, MySQL Connector/J 8.3.0, Testcontainers 2.0.3입니다. [MySQL 8 locking read 문서](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html)상 `FOR SHARE OF table_name`은 지정 테이블의 읽은 행에 공유 잠금을 두며, `SKIP LOCKED`는 일관된 업무 원장이 아니라 queue 형태의 소비에만 적합합니다. 또한 [InnoDB index extension 문서](https://dev.mysql.com/doc/refman/8.0/en/index-extensions.html)처럼 보조 인덱스 뒤에는 clustered primary key가 내부적으로 확장되고 optimizer가 이를 range·정렬 최적화에 사용할 수 있습니다. 이 문서의 fence SQL과 열린 주문 keyset은 이 동작을 전제로 하지만, optimizer 선택은 데이터 분포와 서버 설정에 따라 달라질 수 있으므로 아래 MySQL 실행계획 테스트와 동일 데이터 A/B를 생략하지 않습니다.

2026-07-16 현재 추가된 격리 시나리오는 다음 일곱 가지입니다.

- `FORCE INDEX(PRIMARY)`로 선택한 정확한 주문 PK 잠금이 인접 신규 주문 INSERT를 gap lock으로 막지 않는지
- 열린 주문 capture가 운영과 같은 `(market_type, status, symbol)` 인덱스의 InnoDB 확장 PK를 사용해 `id` keyset을 처리하고 filesort를 만들지 않는지
- 실제 운영 조인 형태의 `FOR SHARE OF f`가 session fence 행만 공유 잠금하여 거래일·시계·시장설정 전역 행 갱신을 막지 않으면서, 해당 fence 공유 잠금이 끝날 때까지 장마감 배타 전환은 기다리고 epoch 변경 뒤 stale 거래가 거부되는지
- 취소·정정의 실제 소유 주문 조인 형태도 `FOR SHARE OF f`가 fence만 잠그고 `stock_order`, `stock_account`, 시장설정, 거래일, 시계 행을 미리 잠그지 않으면서 장마감 fence 전환만 기다리게 하는지
- 잠긴 signal 선두를 `FOR UPDATE SKIP LOCKED`가 기다리지 않고 다음 claim 가능 signal로 진행하는지
- canonical `stock_all.sql`과 운영 순서의 EOD ALTER 11개를 MySQL 8에 적용하고 한 번 더 재실행해도 실패하지 않으며, 적용 전후 `stock_order`·`stock_execution` 인덱스 수가 변하지 않는지
- 최신 canonical을 구형 EOD 스키마 형태로 격리 복원한 뒤 누락 테이블·컬럼·제약·인덱스 생성, 기존 signal 거래일/재시도 시각 backfill, 중간 버전 open-order snapshot 상태 backfill을 실제 ALTER 순서로 실행하고 재적용해도 결과가 같으며, 전후 `stock_order`·`stock_execution`의 전체 `SHOW CREATE TABLE`이 byte-for-byte 동일한지

현재 Docker 없는 로컬에서 strict task 실행 결과는 `tests=2, skipped=0, failures=2, errors=0`이며 두 suite의 컨테이너 초기화 실패입니다. 실제 MySQL 문장은 실행되지 않았으므로 이 실패는 운영 MySQL 통과 증거가 아니며, 실 DB ALTER와 동일 데이터 부하 A/B를 대체하지 않습니다. Docker가 있는 검증 환경에서는 7개 실제 시나리오가 모두 실행되고 skipped/failure/error가 0이어야 합니다. 구형 스키마 migration 테스트는 운영 DB를 읽거나 수정하지 않고 컨테이너 전용 schema를 매 테스트마다 초기화해 실행하며, 실제 적용은 백엔드·배치 종료 확인 후 maintenance window에서만 허용합니다.

### 시간 경계

- 17:59:59 사용자 신규 주문 vs 장마감
- 17:59:59 주문 정정 vs 장마감
- 자동 참여자 주문 vs 장마감
- 상장주관사 주문 vs 장마감
- 체결 후보 확정 vs 장마감
- 장마감 후 취소 요청
- fence 전환 직전/직후 승인 시각 검증

### 정산

- 정산 전 자동 입금 발생
- 정산 전 기업행사 가격 변경
- 정산 중 신규 계좌 생성
- 정산 재실행 결과 hash 동일
- 계좌 200개 chunk 경계 전후 결과 동일
- 취소 전 예약금과 취소 후 현금 대사

### 복구

- 모든 Step 직후 강제 종료
- chunk 중간 종료
- lease 소유 서버 종료
- 3일 이상 미처리
- cycle row가 없는 누락 거래일
- 마지막 청약일 종료 후 다음 PRE_OPEN 복구

### 중복·신호

- full close 신호 여러 개 동시 요청
- signal 선두 lock 경합
- deadlock 후 backoff
- 최대 재시도와 dead letter
- T일 신호를 T+1에 처리
- 과거 epoch 신호 거부
- 실패 cycle이 `next_retry_at` 전에는 claim·JobExecution을 만들지 않고, 같은 phase의 실패 횟수에 따라 30초부터 최대 900초까지 증가하며 앞 phase의 성공 attempt 수는 지수에 포함되지 않는지
- `DEFERRED` cycle이 60초 전에는 JobRepository를 다시 쓰지 않으며 서버 재시작 후에도 같은 재시도 시각을 유지하는지
- due action이 `action-batch-limit`을 넘을 때 완료된 기업행사 stage가 재진입하여 `action-batch-limit + 1`번째 이후 action을 처리하고, 처리 원장 완료행은 중복 지급·중복 상장을 막는지
- due action 청크가 양수 write count를 커밋한 뒤 validation에서 실패하면 `BOUNDED_PROGRESS`와 30초 기본 간격으로 재개되고, 정상 backlog attempt가 60·120·240초 장애 백오프 지수에 포함되지 않는지
- 보고서·정산·시세 Job이 일부 write 후 실패해도 `BOUNDED_PROGRESS`로 오인하지 않고 일반 지수 백오프하여 동일 대형 원장 범위를 30초마다 반복하지 않는지
- 보고서 종목 청크 직후 프로세스를 종료한 뒤 같은 JobInstance를 재시작하면 마지막 영속 `ExecutionContext`의 symbol 다음부터 이어가고, 컨텍스트 커밋 직전 장애를 재현해도 반복 범위가 마지막 한 청크로 제한되며 이전 종목 결과를 삭제하지 않는지
- FAILED JobExecution의 응답이 현재 attempt의 완료 Step write count를 보존하되 이를 계산하려고 주문·체결 원장을 조회하지 않는지

### 필수 시나리오 증거 매핑

아래 판정에서 `소스/H2 확인`은 운영 MySQL의 잠금 대기와 물리 프로세스 종료까지 증명한다는 뜻이 아닙니다. 실제 MySQL 또는 재기동이 필요한 행은 별도로 `운영 대기`를 유지합니다.

| 시나리오 | 현재 직접 증거 | 판정 |
|---|---|---|
| 사용자 신규 주문·정정·장마감 후 취소 vs fence | `TradingSessionFenceServiceTest`의 마감시각·closing fence·late cancel 테스트 | 소스/H2 확인, 실제 18시 경합 운영 대기 |
| 자동 참여자 주문 vs 장마감 | `AutoMarketOrderExecutorTest.placeOrders_closedSessionDropsOrdersBeforeReservationDatabaseAccess` | 계좌·보유·주문 쓰기 전 차단 확인, MySQL 경합 대기 |
| 상장주관사 주문 vs 장마감 | `ListingAutoMarketJobServiceTest.runListingAutoMarket_closedFenceSkipsListingOrderWrites` | 실제 상장주관사 주문 호출 전 차단 확인, MySQL 경합 대기 |
| 체결 후보 확정 vs 장마감 | `InternalOrderBookExecutionServiceTest`의 closing-fence 재검증과 concurrent match-once 테스트 | 소스/H2 확인, `FOR SHARE OF f` 실잠금 테스트는 Docker 부재로 미실행이며 strict task가 실패 |
| fence 직전/직후 승인·잠금 범위 | `StockMysqlConcurrencyTest`의 session-fence·owned-order locking-read 테스트 | 테스트 소스와 fail-closed gate 존재, 현재 Docker 부재로 컨테이너 초기화 실패이므로 운영 미확인 |
| 정산 전 현금·가격 변경 | `PortfolioSettlementJobIntegrationTest`의 `settle_afterFreezeCashChanges_usesFrozenAccountCash`, `settle_afterFreezePriceChanges_usesFrozenClosePrice` | 확인 |
| 동결 후 신규 계좌 | `settle_accountCreatedAfterFreeze_isNotAddedToFrozenCohort`, `MarketClosePostProcessingCompletionServiceTest` | 확인 |
| 같은 frozen cycle 재처리 hash·결과 멱등성 | `settle_sameFrozenCycleCorrectionReexecution_keepsOneSnapshotAndSameInputHash` | correction revision 재처리에서 1행·동일 hash·동일 총자산 확인 |
| 정산 200계좌 chunk 경계 | `settleToday_multiplePages_recordsRealChunkCommitCount` | 기본 200에서 201계좌가 2 commit인 것을 확인 |
| 취소 전 예약금·수량과 취소 후 반환 대사 | `MarketCloseRolloverServiceTest.rolloverClosingPrices_cancelsOpenOrderBookOrdersAndSnapshotsHoldingsPerCloseRun`과 lifecycle 완료 검증 | 확인 |
| 완료 Step 재시작·동일 JobInstance | `StockBatchJobRepositoryIntegrationTest.failedJob_restartUsesSameInstanceAndSkipsCompletedStep` | Spring Batch 재시작 계약 확인, 모든 EOD Step 직후 실제 프로세스 kill은 운영 대기 |
| chunk 중간 종료·보고서 cursor | `PostCloseReportAggregationServiceTest`의 영속 keyset 결과, 보고서 unit의 symbol 단위 교체, 공통 JobRepository 재시작 테스트 | 구성요소 확인, 실제 kill 직전/직후 `ExecutionContext` 경계 실험은 운영 대기 |
| lease 소유 서버 종료 | `PostCloseCycleServiceTest.tryClaim_expiredLease_abandonsOldAttemptAndCreatesNextAttempt`, native/lightweight heartbeat 테스트 | H2 확인, 다중 JVM 종료 검증 대기 |
| 3일 이상 미처리·cycle 없는 날짜 | `SkippedBusinessDateRecoveryServiceTest`의 하루씩 전진·마지막 suffix·미완료 차단 테스트 | 알고리즘 확인, 장기 live 재기동 대기 |
| 마지막 청약일 다음 PRE_OPEN 복구 | `StockBatchJobLauncherTest.corporateCash_restartedAfterMidnight_keepsClosedBusinessDateForFinalDayRecovery`, 기업행사 catch-up 테스트 | 확인 |
| action limit 초과 재진입·중복 지급 방지 | `CorporateActionServiceTest.processCashDividendPaymentStep_moreActionsThanBatchLimit_restartsWithoutDuplicatePayment`와 processing-ledger 테스트 | limit=1의 두 action으로 bounded 재진입·멱등성 확인; 기본 25/26과 최대 200/201 실부하는 A/B 대기 |
| 자동 월급 청크 부분 커밋 후 Step 재시작 | `AutoParticipantCashFlowServiceTest`의 `fundRecurringCash_partialRunRestart_resumesAfterCommittedAccountCursor`, `fundRecurringCash_sameRunKeyAfterCompletedBusinessCommits_doesNotPayAgain` | 같은 실행 cursor 이후만 재개하고 완료 실행 replay는 현금·현금흐름을 중복 반영하지 않음; 실제 프로세스 kill/MySQL 재시작은 운영 대기 |
| full-close 중복 요청 | `PostCloseCycleServiceTest.ensureFullMarketCycle_sameBusinessDate_reusesOneLogicalCycle`, `MarketCloseRolloverServiceTest.rolloverClosingPrices_sameDaySecondClose_reusesCompletedLogicalCycle`, DB 유일키 계약 | 단일 프로세스 확인, 동시 signal/MySQL unique 경합 대기 |
| signal 선두 경합·후속 진행 | `BatchJobSignalProcessorTest.processPendingSignals_skippedTargetJob_defersAndContinuesWithNextSignal`, MySQL `SKIP LOCKED` 테스트 | H2 흐름 확인, 실제 `SKIP LOCKED`는 Docker 부재로 미실행이며 strict task가 실패 |
| signal deadlock/backoff/dead letter | signal reader/writer/processor 테스트와 `PostCloseCycleServiceTest`의 phase별 30~900초 backoff | 확인; 실제 MySQL deadlock 주입은 운영 대기 |
| T일 신호 T+1 처리·과거 epoch 거부 | launcher의 closed business date 유지 테스트, `BatchJobSignalValidationServiceTest` stale epoch 테스트 | 확인 |
| `DEFERRED`·실패 retry window 전 metadata 0 | `PostClosePhaseExecutionServiceTest.execute_retryWindowNotReached_doesNotClaimOrWriteBatchMetadata`, cycle durable retry 테스트 | 확인 |
| `BOUNDED_PROGRESS`와 일반 실패 분리 | `execute_failedResponseWithCommittedProgress_usesBaseContinuationInsteadOfFailureBackoff`, `execute_nonCorporateFailureWithPositiveWriteCount_usesExponentialFailureBackoff` | 확인 |
| FAILED 실행의 실제 Step write count | `StockBatchJobRunnerTest.run_nativeFailedExecution_preservesCommittedStepWriteCount` | JobRepository만 읽는 응답 계약 확인 |

따라서 소스·H2로 확인 가능한 정확성·bounded 처리 시나리오는 보강됐지만, 실제 MySQL 잠금 의미, 다중 JVM 강제 종료, 현재 추정 주문 151만·체결 103만 이상 데이터 A/B는 여전히 승인 전 필수 작업입니다. 이 세 범위를 실행하지 않은 상태에서 “필수 시나리오 전체 통과”라고 표현하지 않습니다.

### 부하

현재 실데이터 규모를 최소 기준으로 사용합니다.

- 주문 최소 151만
- 체결 최소 103만
- 종목 3개 및 확장 100개
- 계좌 100개 및 확장 1만 개
- open order 수백/수만 단계
- 동시 자동 주문·체결·장마감

부하 검증은 fence 미적용 기준선과 동일 데이터·동일 동시성으로 A/B 비교합니다. 단순 처리 성공만 보지 않고 다음을 함께 측정합니다.

- 사용자 주문 API p50/p95/p99와 TPS
- 체결 반응시간 p50/p95/p99와 초당 체결 수
- 거래 트랜잭션당 SQL 수와 fence SQL 실행 횟수
- fence shared/exclusive lock wait 시간
- `stock_market_business_state`, `stock_simulation_clock` row lock 발생 여부
- 자동 주문 chunk당 고유 symbol 수와 fence 조회 수
- DB CPU, buffer-pool read, connection active/pending

정확성 테스트를 통과해도 위 기준선 대비 회귀가 허용치를 넘으면 다음 단계로 진행하지 않습니다.

검증 지표:

- close phase p50/p95/p99
- DB commit p95/p99
- row lock wait
- deadlock
- connection timeout
- snapshot 누락
- duplicate release
- post-freeze mutation
- Job metadata 증가량

---

## 17. 2026-07-16 소스 검증 현황

현재 워킹트리에서 다음 검증을 통과했습니다. 2026-07-16 21:56 KST에는 두 모듈을 공유 Gradle 산출물 경합 없이 하나의 invocation과 `--max-workers=2`로 다시 실행해 같은 전체 결과를 확인했습니다. 22:09 KST에는 정산 reader의 죽은 `eligible` 분기 제거와 EOD volume ALTER 재실행 스캔 guard 보강 후 `./gradlew :stock-back-service:test :stock-batch-service:test`를 한 invocation으로 다시 실행했습니다. 22:27 KST에는 bounded 정산 shadow 진단 계약을 추가한 뒤 두 모듈을 각각 `--rerun-tasks`로 다시 실행해 back 490건, batch 850건이 모두 skipped/failure/error 0임을 확인했고, shadow 가드를 다음 야간 phase attempt 부재까지 강화한 현재 트리도 22:31 KST에 batch 전체 850건을 다시 실행해 같은 결과를 확인했습니다. 22:45 KST에는 문서·실제 Job 구조 재감사 후 두 모듈을 한 invocation, `--rerun-tasks --max-workers=2`로 다시 실행해 back 490건·batch 850건, skipped/failure/error 0을 확인했습니다. 이 재감사에서 정산 문서의 가상 4-Step 표기를 실제 3-Step 구조로 바로잡았고, 대사와 phase 전이를 분리하는 불필요한 Step은 추가하지 않았습니다. 22:52 KST에는 두 legacy 내부 close phase 복구 테스트를 추가한 현재 batch 전체 852건을 `--rerun-tasks --max-workers=2`로 실행해 skipped/failure/error 0을 확인했습니다. 23:12~23:16 KST 최종 감사에서는 현재 트리의 back 490건과 batch 852건을 각각 `--no-parallel --max-workers=2`로 재실행하고, 테스트 전용 중복 기업행사 public 오케스트레이션 두 개를 제거한 뒤 실제 Step 순서 집중 테스트 41건과 batch 전체 852건을 다시 통과시켰습니다. 프론트도 lint·typecheck·production build와 contract·navigation·auth·corporate-action 검증을 순차 실행했습니다.

- `./gradlew :stock-batch-service:test --rerun-tasks --max-workers=2` — 852건, skipped/failure/error 0
- 전체 suite에서만 자동시장 프로필 선택 테스트가 간헐적으로 실패하던 원인은 운영 랜덤값이 아니라 test profile에서 별도 설정인 일일 regime·프로필 큐 reconcile·자동 주문 만료·상장주관사 scheduler가 살아 있어 공유 H2/메모리 큐를 건드린 격리 공백이었다. test profile에서 네 scheduler를 명시적으로 끈 뒤 전체 suite를 강제 재실행했다. Docker 없는 `mysqlTest`가 조용히 skip되지 않게 하는 검증 gate, read-only 정산 shadow SQL 계약, 두 legacy 내부 close phase 복구 변형을 포함한 현재 전체 수가 852건이다. production 설정·주문 SQL·체결 SQL에는 변화가 없다.
- `./gradlew :stock-back-service:test` — 490건, skipped/failure/error 0
- Gradle `dependencyInsight`로 실제 해석된 버전을 다시 확인했다. 런타임 Spring Batch는 `6.0.2`/Spring Boot Batch는 `4.0.2`, MySQL 동시성 테스트의 Connector/J는 `8.3.0`, Testcontainers는 `2.0.3`이다. 로컬 `spring-batch-core-6.0.2.jar`의 `JobRepository`·`JobParameter` API도 `deleteJobInstance(JobInstance)`와 identifying flag를 직접 확인해, 문서가 다른 major 버전의 재시작·metadata 삭제 API를 전제로 하지 않음을 검증했다.
- MySQL 8 공식 locking-read 계약을 재대조했다. `FOR SHARE OF fence`는 읽은 fence 행의 공유 잠금을 transaction 종료까지 유지하고, exact unique/PK 조건은 해당 record만 잠그지만 비고유 range 조건은 next-key/gap lock을 만들 수 있다. `SKIP LOCKED`는 불일치 view를 허용하는 queue-like claim에만 사용하고, 선택된 주문 PK·fence·계좌·보유 원장의 정확성 잠금에는 사용하지 않는 현재 원칙을 유지한다.
- `bootBuildInfo`를 워킹트리 변경 상태와 CI override 양쪽으로 재생성해 로컬 SHA가 각각 `b26da0334f74-dirty`, `eacb2484517d-dirty`로 표시되고 `BUILD_SHA=ci-content-addressed-test` 주입 시 두 서비스 모두 해당 값이 우선되는 것을 확인
- `./gradlew :stock-batch-service:mysqlTest --rerun-tasks` — Docker 부재 시 두 suite가 컨테이너 초기화에서 명시적으로 실패(`tests=2, skipped=0, failures=2, errors=0`); 실제 MySQL 통과로 판정하지 않음
- `StockEodSettlementShadowSqlContractTest` — 진단이 200계좌 PK cursor, 계좌 선두 인덱스, read-only consistent snapshot만 사용하고 `stock_execution`·영속 write·locking read를 사용하지 않는지 검증. 잘못된 cycle은 고의 SQL 오류에 의존하지 않고 `guard_status=REJECTED`와 비교 0건으로 fail-closed하며 페이지 불일치 수를 함께 반환
- 2026-07-16 18:49 및 19:28 KST 운영 MySQL 8.0.32 읽기 전용 schema 재확인 — EOD 신규 핵심 테이블 0/11, signal 신규 컬럼 0/10, `portfolio_snapshot`의 EOD 귀속·검증 컬럼 0/6으로 실 ALTER 미적용을 확인했다. 기존 보유지표 세 컬럼(`holding_quantity`, `reserved_sell_quantity`, `holding_position_count`)은 이미 존재하므로 EOD 신규 컬럼 수에서 분리했다. 18:49에는 주문장 3개가 모두 CLOSED였고, 19:28에도 `stock_simulation_clock.running=1`과 heartbeat 갱신을 확인했다. 배치가 가동 중이므로 ALTER를 실행하지 않았고 데이터 write도 수행하지 않았다.
- 같은 live 점검에서 백엔드·배치는 모두 health `UP`이었지만 2026-07-15 15:12부터 IntelliJ `out/production`으로 기동된 프로세스였다. 2026-07-16 20:42 KST에 PID 14622(batch)·14624(back)가 계속 같은 `out/production` classpath로 실행 중임을 다시 확인했고, 해당 산출물에는 현재 소스의 `PostCloseCoordinatorScheduler`, `PostCloseReadinessService`, `TradingSessionFenceService`, `EodOperationsOverviewService` class가 없었다. 따라서 현재 프로세스를 새 EOD 구현의 runtime 검증으로 사용하지 않으며 에이전트가 종료하거나 재기동하지 않는다.
- 두 서비스의 시작 schema readiness metadata 조회를 실제 JDBC connection catalog로 한정했다. 같은 MySQL 인스턴스의 다른 DB에 동명 테이블이 있어도 현재 business schema의 누락 컬럼·인덱스를 대신 충족한 것으로 오판하지 않으며, 이 조회는 시작 시 한 번만 실행되어 주문·체결 hot path 비용을 늘리지 않는다.
- `npm run lint`
- `npm run typecheck`
- `npm run build`
- `npm run verify:contract`
- `npm run verify:navigation`
- `npm run verify:auth`
- `npm run verify:corporate-actions`
- EOD overview가 singleton business-state/clock과 cycle 제어행만 읽어 원시 시뮬레이션 시각을 반환하고, 고용량 주문·체결·스냅샷 history를 읽지 않는 `EodOperationsOverviewServiceTest`
- 장마감 취소 전 종목별 요약에서 반환 매수 예약금·매도 예약수량을 동결 완료 시 cycle metric에 한 번 저장하고, API·프론트가 그 요약만 표시하는 `MarketCloseRolloverServiceTest`와 `EodOperationsOverviewServiceTest`. 이 변경은 `stock_order`·`stock_execution` 인덱스와 정규장 SQL·잠금·commit 수를 바꾸지 않는다.
- EOD 화면의 원시 시각·cycle/attempt 경과 표시 변경 후 `npm run lint`, `npm run typecheck`, `npm run build`를 재실행했고, UI coherence scanner에서 컴포넌트 내부 hard-coded accent 색상이 0건임을 확인
- 배치·백엔드·프론트·공통 모듈과 루트 프론트 계약 스크립트 `git diff --check`
- 11개 운영 ALTER의 back/batch byte-level mirror 비교와 `USE STOCK_SERVICE;` 확인
- MySQL migration 테스트가 11개 운영 ALTER를 모두 순서대로 적용·재적용하는지 확인. 누락돼 있던 자동 월급 실행 cursor ALTER를 목록과 구형 스키마 downgrade 경로에 포함했다.
- signal ALTER가 MySQL 예약어 `SIGNAL`을 테이블 별칭으로 사용하지 않는 계약
- EOD ALTER가 `stock_order`·`stock_execution`에 인덱스를 추가하지 않는 DDL 계약. 누락돼 있던 체결 손익 요약 ALTER도 전체 11개 정적 검사 목록에 포함했다.
- nullable legacy open-order snapshot backfill은 동적 guard 안에서만 `stock_order` PK를 조인하고, 컬럼이 이미 `NOT NULL`인 정상 재적용에서는 snapshot/order 스캔을 실행하지 않는 DDL 계약
- 체결 손익 요약 ALTER는 컬럼 DDL 성공 뒤 원장 backfill만 실패한 재실행에서도 소형 요약 금액 불변식으로 중간 상태를 감지한다. 정상 완료 재실행은 `stock_execution`을 다시 읽지 않으며, MySQL migration 테스트에는 default-zero 중간 상태를 만든 뒤 재실행해 손익 7개 필드가 복구되는 시나리오를 추가했다. 현재 Docker 부재로 strict task가 컨테이너 초기화에서 실패해 이 동적 시나리오는 미실행이고 정적 mirror/guard 계약만 로컬 통과했다.
- 종목 단위 마감·야간 보고서에서 nullable symbol predicate를 금지하는 SQL 계약
- `cycleId`가 있는 native·lightweight EOD 실행에서만 MDC가 설정되고 실행 후 기존 문맥으로 복원되는 `StockBatchJobRunnerTest`
- metadata archive 후보의 완료·종료시각 이중 검증, instance/execution/step 상한, 열린 시장 거부, purge allow-list와 archive-only 기본값을 검증하는 단위 테스트
- 실제 Spring Batch 6.0.2 `JobRepository`로 완료 JobInstance를 생성한 뒤 compact archive와 framework delete가 함께 동작하고 archive의 `PURGED_AT`이 보존되는 통합 테스트
- 정산 완료 검증이 올바른 frozen 출력은 승인하고, 입력 hash가 같더라도 총자산 또는 보유지표가 다른 저장 결과는 `PORTFOLIO_SETTLED` 전이를 거부하는 통합 테스트
- coordinator 비활성 호환 시세 쓰기가 close-boundary 공유 permit을 먼저 얻고, 열린 시장에서는 허용되지만 `CLOSE_REQUESTED`부터 `EXECUTION_DRAINED`까지는 가격 쓰기 전에 거부되는 회귀 테스트
- 본 문서가 직접 지칭하는 코드·DDL·설정·문서의 구체 파일명 36개가 현재 워크스페이스에서 모두 해석되는 참조 감사. 패턴 표기인 `*_alter.sql`은 개별 파일 수에서 제외한다.
- 신규 production 파일의 타입명 참조 감사. 명백한 고아 파일은 없었고, repository·서비스 사용 없이 JPA metamodel에만 등록되던 두 entity와 전용 ID/enum 네 파일은 제거했다. 테스트 H2 생성 책임은 `StockTestEodSchemaInitializer`, 운영 검증 책임은 canonical DDL과 `StockSchemaReadinessValidator`로 명시했다.
- 정상 close는 `CLOSE_REQUESTED` 안에서 주문 차단·in-flight drain을 수행한 뒤 `LEDGER_FROZEN`으로 전이하므로, 관리자 타임라인에서 실제로 별도 저장되지 않는 `ORDER_ENTRY_CLOSED`·`EXECUTION_DRAINED` 두 칸을 제거하고 내부 절차로 설명했다. enum/DDL 값은 기존 cycle·복구 호환을 위해 유지한다.
- 자동 월급은 coordinator 모드에서 거래일당 한 번만 due-sweep하며 sub-day 회차를 소급하지 않는 실제 동작에 맞춰 신규·수정 API와 관리자 선택지를 `DAY`·`MONTH`·`YEAR`로 제한했다. 금액이 양수인 요청뿐 아니라 금액이 `0`이거나 비어 있어 지급이 꺼진 수정 요청도 명시적인 `SECOND`·`MINUTE`·`HOUR` 단위를 저장하지 못하게 쓰기 경계에서 거부한다. 과거 enum/표시는 판독 호환으로 유지하고 자동 데이터 변환은 하지 않는다.
- 2026-07-16 운영 MySQL을 읽기 전용으로 집계해 활성 개별 양수 지급 설정 11건과 양수 프로필 기본값 26건이 모두 `DAY`이고, 양수 sub-day 설정은 0건임을 확인했다. 따라서 이 정책 적용은 현재 저장 데이터와 충돌하지 않으며 catch-up 지급도 만들지 않는다.
- 자동 월급의 `REQUIRES_NEW` 계좌 청크와 단일 tasklet 재시작 경계를 추가 감사했다. 실행당 1행 cursor를 두고 현금·현금흐름·cursor를 같은 청크 트랜잭션에 묶어 후반 청크 실패 뒤 중복 지급을 막았으며, 이 테이블과 SQL은 야간 현금 경로에서만 접근하고 주문·체결 hot path에는 연결하지 않았다.
- 종목 단위 rollover·미체결 정리 신호는 관리자 상태 변경이 먼저 종목 fence를 닫는다는 호출 순서만 신뢰하지 않는다. signal 검증기가 `OPEN` fence를 Job 생성 전에 영구 거부하고, `MarketCloseRolloverService`도 열린 종목이면 cycle 생성·symbol lock·주문 조회 전에 다시 거부한다. 전체시장 18시 close에는 추가 쿼리가 없고, 수동 종목 제어 경로에만 시장설정 PK 조회 1회가 추가된다.
- 기업행사 실패 후 빠른 재개가 일반 FAILED Job 전체로 퍼지지 않도록 `BOUNDED_PROGRESS` 허용 범위를 CASH의 `OVERNIGHT_CASH_APPLIED`와 PREOPEN의 `REPORTS_AGGREGATED` 두 진입 phase 모두에서 회귀 테스트했다. 보고서·정산·시세 실패는 계속 일반 backoff를 사용한다.
- PRE_OPEN 프로필 큐가 Redis `enqueue`·`remove` 예외를 0건 성공처럼 숨길 수 있던 fail-open 공백을 발견해 수정했다. PRE_OPEN 전용 경로는 전용 zset을 Lua 1회로 원자 교체하고 `ZCARD`가 DB schedule의 distinct profile 수와 다르면 phase를 실패시킨다. Redis 예외도 전파한다. 정규장 10분 복구는 기존 best-effort를 유지하며 `stock_order`·`stock_execution` 조회·쓰기와 거래 transaction에는 변경이 없다. Redis·memory 구현과 reconcile service의 성공·부분교체 실패 집중 테스트를 추가했다.
- PRE_OPEN fence가 DDL enum에만 있고 실제 상태 전이에 사용되지 않던 공백을 수정했다. 활성 종목은 다음 준비 거래일의 `PREPARING`, 비활성 종목은 `CLOSED`로 전환하며 반복 10초 동기화는 config/fence 소형 행의 read-only fast path로 즉시 반환한다. 개장 readiness는 정산·거래일·시장·fence·가격 snapshot·기업행사 CASH/PREOPEN·regime·Redis profile queue·build/schema의 10개 결과를 cycle당 한 번 저장하고, 실패 결과도 다음 재시작을 위해 남긴다.
- `PostCloseReadinessServiceTest.readinessQuery_doesNotReadOrWriteHotTradingLedgers`와 `EodOperationsOverviewServiceTest`가 readiness 생성 및 15초 관리자 조회에서 `stock_order`·`stock_execution`·`stock_holding` 직접 SQL을 금지한다. readiness 저장은 cycle당 10행, Redis 비교는 고정 profile enum 범위, 관리자 조회는 cycle PK/display-order 범위여서 거래량과 함께 증가하지 않는다.

이 검증은 Java/H2·정적 계약·Next.js production build 범위입니다. 실제 MySQL 8의 `EXPLAIN ANALYZE`, 현재 추정 주문 151만/체결 103만 이상 기준 A/B, 동시 주문·체결·18시 fence 경합, 운영 ALTER 실행은 아직 수행하지 않았습니다. 서버 종료 확인 없이 ALTER를 적용하거나 성능 회귀가 없다고 최종 판정하지 않습니다.

### 17.1 구현 완결성 재감사 판정

2026-07-16 재감사 결과를 “소스 존재”와 “운영 승인”으로 분리하면 다음과 같습니다.

| 영역 | 소스·정적 계약 | 운영 승인 | 판정 |
|---|---|---|---|
| 종목 session fence와 수동 마감 이중 guard | 구현·테스트 완료 | MySQL 경계 동시성 A/B 대기 | 소스 완료 |
| 논리 cycle·attempt·lease·오래된 거래일 복구 | 구현·H2 계약 완료 | 다중 프로세스/장기 중단 복구 대기 | 소스 완료 |
| bounded 주문 캡처·취소·현금/보유/가격 불변 스냅샷 | 구현·재시작 테스트 완료 | 운영 ALTER·실데이터 대사 대기 | 소스 완료 |
| frozen snapshot 기반 정산·cohort 완료 판정·input hash | 구현·201계좌 chunk 경계 테스트와 계좌 PK 200개 단위 read-only legacy/frozen/stored shadow SQL 계약 완료 | 운영 ALTER 후 첫 cycle 전체 페이지 shadow 실행 대기 | 소스 cut-over·진단 준비 완료, 운영 미승인 |
| 시간대 coordinator와 heavy-job admission | 구현·scheduler 테스트 완료 | 실제 시간대 cycle 관찰 대기 | 소스 완료 |
| DB 전체 부하 신호 기반 야간 admission | 정책과 임계값만 문서화; 신뢰 가능한 DB-wide active/lock-wait p95 입력 미구현 | Performance Schema 또는 exporter 조회비용·권한·장애 정책 A/B 승인 대기 | 소스 미완료, 가짜 pool-only gate 미추가 |
| PRE_OPEN 자동시장 프로필 큐 | 단일 Redis Lua exact-replace·건수 검증·fail-closed 및 memory/Redis/service 테스트 완료. 정규장 best-effort 경로는 유지 | 운영 Redis 장애·복구 cycle 관찰 대기 | 소스 완료 |
| 기업행사 CASH/PREOPEN 분리·처리 원장·bounded 재시작 | 구현·테스트 완료 | 실제 이벤트 복구 cycle 대기 | 소스 완료 |
| 자동 월급 | 정규장 차단·야간 1회 due-sweep·청크 재시작 cursor 멱등성, 신규/수정 `DAY`·`MONTH`·`YEAR` 제한 구현. 현재 운영 DB의 양수 설정 37건은 모두 `DAY` | 실제 야간 cycle·재시작 관찰 대기. 현재 데이터 마이그레이션 충돌은 없음 | 소스 완료 |
| signal lease·backoff·deferred·dead letter | 구현·테스트 완료 | 운영 deadlock/lease 소유권 회복 관찰 대기 | 소스 완료 |
| 관리자 EOD read model·15초 화면 한정 polling | 핵심 cycle·대사·재시도, 원시 시뮬레이션 시각, cycle/최근 attempt 시작·완료·경과, 취소 뒤 반환 매수 예약금·매도 예약수량과 고정 10개 readiness·기업행사 미완료 수 표시 구현. 정상 phase는 coordinator 자동 선점만 허용 | live browser/API 관찰과 실제 EOD metric 확인 대기 | 표시·명령 소스 완료, 운영 미승인 |
| EOD 상세 스냅샷 보존·archive | cycle cohort 단위 원칙만 문서화; 자동 purge 미구현 | 회계 보존일·archive 저장소·bounded 삭제 A/B 승인 대기 | 정책 미완료 |
| DDL·ALTER·H2·reset 계약 | EOD ALTER 11개 mirror, `USE STOCK_SERVICE;`, hot-ledger 테이블 ALTER·신규 인덱스 비변경 계약 완료. 단, 체결 손익 요약의 최초/중단복구 backfill은 `stock_execution`을 읽고 nullable legacy open-order snapshot 보정은 `stock_order`를 PK 조인하므로 서버 종료 유지보수 창 전용이다. 후자는 컬럼이 아직 nullable일 때만 실행되도록 보호해 정상 재적용 시 두 테이블을 스캔하지 않는다. | 서버 종료 확인 후 운영 적용 대기 | 정적 완료 |
| 주문·체결 거래량 비회귀 | hot path 예산·bounded 상한·정적/단위 계약 완료 | 현재 추정 주문 151만·체결 103만 이상 동일 데이터 MySQL A/B 대기 | 최종 미승인 |
| Git 산출물 | 로컬 컴파일·테스트 대상 소스는 존재 | 계획 문서와 핵심 신규 production 파일이 하위 저장소에서 미추적 | 배포 미완료 |

따라서 “계획 전부 구현 완료”라고 표현하지 않습니다. 핵심 EOD 소스·정적 계약, 자동 입금 세부 정책과 bounded 정산 shadow 진단은 구현됐지만 운영 ALTER, MySQL 동시성·대용량 A/B, 첫 cycle shadow 실제 실행, DB 전체 부하 신호 기반 admission, 재기동 live 관찰이라는 운영 승인 게이트가 남았습니다. EOD 상세 스냅샷 보존·archive 정책도 미완료입니다. 정상 phase 수동 명령은 미완료 기능이 아니라, 10초 coordinator와 실행 정책을 중복시키는 불필요한 제어면으로 판정해 대상에서 제외했습니다. 운영 승인 게이트가 끝나기 전에는 배포 완료나 거래량 비회귀를 확정하지 않으며, 보존 정책 역시 구현 범위에서 제외된 것으로 숨기지 않습니다.

불필요 코드 감사에서는 현재 미추적 production Java 타입(batch 36개, back 10개)과 front 신규 export 2개의 소스·테스트 참조를 다시 확인했고 단순 고아 타입은 없었습니다. 이미 제거한 JPA 전용 고아 타입 외에는 삭제 근거가 없는 운영 컴포넌트를 기계적으로 줄이지 않았습니다. 정산 reader의 운영 호출에서 항상 `true`였던 테스트 전용 `eligible` boolean 분기는 제거하고, 테스트도 실제 권위 조건인 frozen 입력 `reconciliation_status = 'MATCHED'` 필터를 검증하도록 바꿨습니다. coordinator가 켜지면 즉시 반환하는 기존 개별 scheduler와 기업행사 `ALL` 분기는 cut-over 실패 시 되돌릴 수 있는 호환 경로이므로 현재는 dead code가 아니지만, 운영 ALTER·shadow·MySQL A/B·한 거래일 live 관찰까지 통과한 뒤 별도 제거 대상으로 재감사합니다. `ORDER_ENTRY_CLOSED`·`EXECUTION_DRAINED` enum/DDL 값은 정상 UI 단계에서는 숨기되 기존 데이터·복구 호환을 위해 유지합니다. `MarketCloseRolloverWriter`, `CorporateActionService`, `MarketSessionFenceService`처럼 큰 클래스는 책임 분리 후보지만, 지금 쪼개면 close-critical SQL·트랜잭션 경계를 넓은 diff로 흔들 수 있으므로 실제 MySQL A/B 전에는 기계적 분할을 하지 않습니다.

이번 재감사에서는 PRE_OPEN Redis 프로필 큐의 실패 은폐, 실제로 사용되지 않던 `PREPARING` fence, 관리자 반환 대사 수치 누락, readiness 실패 상세의 비영속화를 추가 구현이 필요한 소스 공백으로 확인했습니다. 큐는 interface의 strict exact-replace·bounded snapshot 계약과 Redis·memory 구현 및 집중 테스트만 보강했고, 반환 수치는 기존 `stock_post_close_cycle_metric` 행에 두 컬럼만 추가해 동결 완료 경로에서 한 번 기록하도록 보완했습니다. readiness는 cycle당 정확히 10행인 전용 제어 테이블에 한 번 기록하고 overview는 해당 PK 범위만 읽습니다. 별도 scheduler·주문/체결 index·정규장 주기 집계·wrapper 계층은 추가하지 않았습니다. 그 밖의 남은 항목은 코드 양으로 해결할 문제가 아니라 운영 ALTER, 실제 MySQL 동시성·거래량 A/B, frozen 정산 shadow, live 재기동과 정책 승인 게이트입니다.

시간대 coordinator 재감사에서는 앞 단계 지연 시 05:00 이후에도 메타데이터 보존·빈 보유 정리가 새로 시작될 수 있는 공백을 추가로 수정했습니다. 준비 거래일의 readiness 시각 30분 전을 cutoff로 계산하여 그 이후에는 두 비필수 작업을 DB·JobRepository 호출 전에 건너뛰고, 필수 PRE_OPEN 수량·가격 변환만 계속합니다. 이 변경은 coordinator 제어 분기에만 있고 정규장 주문·체결 SQL·인덱스·잠금·커밋 수를 바꾸지 않습니다. 반면 DB pressure admission은 batch Hikari pool 하나만 보는 구현으로는 전체 부하를 오판하므로, 신뢰 가능한 DB-wide 신호가 승인될 때까지 명시적으로 미구현 상태로 둡니다.

같은 경로에서 선택적 빈 보유 정리의 명시적 `FAILED`가 필수 PRE_OPEN 변환까지 막던 비대칭도 수정했습니다. 메타데이터 보존과 마찬가지로 실패는 별도 Job 이력과 WARN에 남기고 권리락·분할·상장 변환을 계속하며, 서비스 종료나 공통 admission 경합처럼 `SKIPPED`된 경우에는 기존대로 phase를 전진시키지 않습니다. 이는 CLOSED/PRE_OPEN coordinator 분기만 바꾸며 주문·체결 hot path의 SQL·잠금·commit은 0건 증가입니다.

최종 DDL·관리자 UI·불필요 코드 감사에서는 문서의 구체 파일 참조 36개가 모두 해석되고, 운영 ALTER 11개가 back/batch byte 동일이며 모두 `USE STOCK_SERVICE;`로 시작하는지 다시 확인했습니다. canonical DDL·H2·두 reset 스크립트와 계약 테스트에도 신규 cycle/fence/snapshot/action/cash 구조가 연결돼 있습니다. 관리자 EOD 조회는 `system-eod` 화면에서만 15초 주기로 실행되고 background polling을 하지 않으며, cycle·metric·attempt·readiness 최대 10행·signal 각 제어 테이블만 읽습니다. 재시도도 주문·체결 원장을 건드리거나 Job을 즉시 실행하지 않고, 시장이 닫힌 상태에서 가장 오래된 실패 cycle의 backoff만 해제합니다. 신규 production 타입은 선언 파일 외 참조가 없는 항목이 0개였고 프론트 신규 두 파일도 실제 라우팅/props 조립에서 사용됩니다. 양쪽 `stock_corporate_action_processing_alter.sql`의 불필요한 EOF 빈 줄만 제거해 mirror와 untracked-file whitespace 검사를 맞췄으며, 기능·SQL·잠금·commit에는 변화가 없습니다.

체결 계좌 일일요약의 사용자 안내도 재감사했습니다. 정상 flush 간격은 약 30초지만 DB flush 실패·프로세스 재기동·고유 account-day 슬롯 상한 초과에서는 야간 REPORTS 원본 대사까지 더 늦어질 수 있으므로, 화면과 README의 절대 상한처럼 보이던 “최대 30초” 문구를 “정상 집계 시 약 30초, 장애·재기동 시 야간 대사 후 확정”으로 바로잡았습니다. 이는 표시·문서만 변경하며 체결 after-commit, 요약 flush, 주문·체결 SQL·인덱스·잠금·commit 수에는 변화가 없습니다.

### 17.2 실행 거래일 컨텍스트 후속 감사

2026-07-16 후속 감사에서 수동 월급 신호가 `requested_business_date`와 `expected_cycle_id`를 claim·검증 단계에서는 사용하지만, 실제 `StockBatchJobLauncher` 호출에서는 `signal.id`만 전달하는 공백을 확인했습니다. 원시 시뮬레이션 날짜가 활성 거래일보다 앞선 장애 복구 상황이면 검증은 T 거래일로 통과한 뒤 Spring Batch JobInstance가 T+n 날짜로 만들어질 수 있었습니다.

이를 다음과 같이 최소 수정했습니다.

- 수동 월급 신호는 `requested_business_date`, `expected_cycle_id`, `signal.id`를 launcher까지 전달한다.
- launcher는 singleton active-business-date를 실행 직전 한 번 다시 읽어 요청 거래일과 일치하는지 확인하고, 요청 거래일의 full-market cycle ID도 다시 검증한 뒤 identifying `businessDate` JobParameter로 사용한다. 자동 지급이 켜져 있거나 정규장이라 스킵되는 수동 경로는 이 DB 조회 전에 반환한다.
- `expected_cycle_id`는 signal 원장에 이미 보존되고 launcher가 실행 직전에 검증하므로, 이를 `cycleId` JobParameter로 중복 저장하지 않는다. 수동 월급은 coordinator가 소유한 phase Job이 아니어서 `cycleId`를 붙이면 phase-attempt listener와 lease heartbeat가 잘못 연결될 수 있기 때문이다.
- 수동 내부 API의 월급·전체 마감·종목 마감·미체결 취소·포트폴리오 정산은 원시 날짜 대신 `stock_market_business_state.active_business_date`를 사용한다.
- scheduler의 정상 당일 경로와 coordinator가 명시적인 과거 거래일을 넘기는 overload는 그대로 유지한다.

이 보완은 저빈도 수동/내부 배치 진입점에서 singleton business-state 행을 한 번 읽는 변경입니다. 사용자 주문, 주문 정정, 자동 주문, 상장주관사 주문, 체결 worker의 SQL·인덱스·잠금 순서·commit 수에는 변경이 없습니다. 따라서 정규장 거래량 경로의 추가 왕복은 0건이며, 거래량 비회귀 최상위 승인 게이트도 그대로 유지합니다. `StockBatchJobLauncherTest`와 `BatchJobSignalProcessorTest`를 강제 재컴파일해 요청 거래일 보존과 원시 날짜 선행 시 active-business-date 사용을 검증했습니다.

현재 실행 환경도 소스 완료와 운영 적용을 분리해 판정해야 합니다. 점검 시점의 백엔드·배치 프로세스는 모두 health `UP`이지만 2026-07-15에 IntelliJ `out/production`으로 시작한 구형 클래스이며, 신규 `PostCloseCoordinatorScheduler`, `MarketSessionFenceService`, `EodOperationsOverviewService` class가 실행 산출물에 없습니다. 별도로 2026-07-16 생성한 back·batch `bootJar`에는 이 신규 클래스와 schema readiness validator가 포함되고 build SHA도 각각 `eacb2484517d-dirty`, `b26da0334f74-dirty`로 기록됨을 확인했지만, 아직 실행하지 않았고 dirty 산출물이라 운영 승인 대상도 아닙니다. 같은 날 읽기 전용 DB 재조회에서도 신규 cycle/fence/snapshot/action-ledger/readiness EOD 테이블은 12개 중 0개, signal lease 컬럼은 10개 중 0개였고, `portfolio_snapshot`의 신규 필수 컬럼은 9개 중 기존 보유지표 3개만 존재했습니다. 기존 EOD 관련 테이블 5개를 더해도 계획 스키마가 준비된 상태가 아닙니다. 따라서 현재 서버를 그대로 재시작하면 안 되며, 사용자가 백엔드와 배치 종료를 확인한 뒤 ALTER 적용 → 승인 가능한 clean SHA 산출물 재기동 → startup schema readiness → MySQL 동시성·거래량 A/B 순서로 진행해야 합니다. 이 상태는 “계획 소스가 구현됨”을 “운영 반영 완료”로 오인하지 않기 위한 배포 차단 조건입니다.

### 17.3 MySQL 동시성 검증 범위 재감사

현재 `StockMysqlConcurrencyTest`의 동적 MySQL 계약은 정확한 주문 PK 잠금의 인접 INSERT 비차단, 기존 상태·종목 인덱스를 사용한 주문 캡처 keyset, 공유 fence를 보유한 in-flight 주문을 close가 기다린 뒤 stale 주문을 거부하는 drain barrier, 소유 주문 조회가 fence 외 hot-ledger 행을 선점하지 않는지, signal `SKIP LOCKED`가 잠긴 선두를 건너뛰는지의 5개 원시 동시성 계약만 다룹니다. 사용자 주문·정정, 자동 참여자 주문, 상장주관사 주문, 실제 체결 서비스와 장마감의 전체 transaction 경합은 각각 H2 통합·단위·소스 잠금순서 계약으로만 검증돼 있습니다.

따라서 Testcontainers 클래스가 존재한다는 사실을 “첨부 문서의 MySQL 경계 시나리오 전체 완료”로 해석하지 않습니다. Docker가 없는 현재 환경에서 실행하지 못하는 서비스 조립 테스트를 대량으로 추가하는 것은 검증되지 않은 테스트 코드만 늘리므로 하지 않았습니다. 운영 ALTER 후 별도 복제 데이터베이스에서 다음을 실제 서비스 진입점과 동일한 transaction manager로 실행해야 운영 승인할 수 있습니다.

- 17:59:59 사용자 신규·정정, 자동 주문, 상장주관사 주문, 체결 후보 확정과 `beginClose`의 동시 경합
- fence 공유 잠금 선행/close 배타 잠금 선행 두 순서에서 승인·동결 결과 대사
- phase별 강제 종료, lease 만료·다른 owner 인계, 3일 이상 누락 거래일 순차 복구
- 정산 중 신규 계좌·현금·가격 변경과 동일 cycle 재실행 `input_hash` 불변
- 중복 full-close 신호, signal 선두 잠금, deadlock backoff, 마지막 청약일 다음 PRE_OPEN 복구
- 현재 추정 주문 151만·체결 103만 이상 동일 데이터에서 변경 전·후 주문 TPS, 주문 API p95, 체결 반응 p95/p99, commit·row-lock·deadlock·timeout 비교

이 항목들은 현재 코드량을 더 늘리는 승인 조건이 아니라, 이미 구현한 fence·cycle·snapshot 경로를 실 MySQL에서 통과시켜야 하는 배포 게이트입니다. 서버 종료와 복제/백업 확인 전에는 운영 DB에서 실행하지 않습니다.

### 17.4 내부 close phase 복구 경로 재감사

기존 데이터·복구 호환을 위해 남긴 `ORDER_ENTRY_CLOSED`와 `EXECUTION_DRAINED`가 enum·DDL·조회 조건에는 있었지만, `MarketCloseRolloverService`는 `CLOSE_REQUESTED`보다 뒤인 phase를 모두 이미 처리된 것으로 간주해 0건 반환하고 있었습니다. `PortfolioSettlementScheduler`는 두 phase를 가장 오래된 미정산 cycle로 계속 선택하고 coordinator도 직접 전진시키지 않으므로, 해당 상태의 cycle은 영구 대기할 수 있었습니다.

이를 정상 close 작업을 늘리지 않는 범위에서 다음처럼 수정했습니다.

- freeze 재개 대상은 `CLOSE_REQUESTED`, `ORDER_ENTRY_CLOSED`, `EXECUTION_DRAINED` 세 phase로만 명시한다.
- 재개 시 저장된 현재 phase를 그대로 claim하고 기존 bounded 주문 캡처·취소·스냅샷 checkpoint를 재사용한다.
- `LEDGER_FROZEN` 이후 phase와 완료 cycle은 기존처럼 즉시 0건 반환한다.
- 두 호환 phase 각각에서 완료된 close run과 `LEDGER_FROZEN/PENDING`으로 전이하는 회귀 테스트를 추가했다.

이 보완은 장애 복구 시 cycle 제어행을 선택하는 분기만 변경합니다. 사용자 주문·정정, 자동 주문, 상장주관사 주문, 체결 worker의 SQL·잠금·commit 수는 변하지 않고, 정상 `CLOSE_REQUESTED` 경로에도 추가 DB 왕복이 없습니다. 호환 phase를 실제 복구 가능하게 만들어 유지 근거를 코드와 일치시켰으므로, 이를 삭제하거나 별도 Step·wrapper를 추가하지 않았습니다.

같은 시점의 실행 환경도 읽기 전용으로 다시 확인했습니다. PID 14622 배치와 PID 14624 백엔드는 여전히 2026-07-15 IntelliJ `out/production` 산출물로 실행 중이고 두 health endpoint는 `UP`이지만 신규 EOD 핵심 class 세 개는 산출물에 없습니다. 운영 schema readiness 대상 17개 테이블 중 존재하는 것은 5개이고 12개가 누락됐으며, signal 신규 컬럼은 0/10, `portfolio_snapshot` EOD 귀속·검증 컬럼은 0/6입니다. 서버나 DB 상태는 변경하지 않았고, 이 상태에서는 ALTER·새 코드 runtime 검증·거래량 비회귀 A/B를 완료로 표시하지 않습니다.

### 17.5 2026-07-17 현재 Git 변경 전수 재감사와 직접 실행 경계 보완

현재 `stock-back-service`, `stock-batch-service`, `stock-front-service`, `web-common-core`의 추적·미추적 변경과 연결된 주문·체결·EOD 흐름을 다시 정적 감사했습니다. 이번 감사에서는 사용자가 금지한 서버 종료·재기동, health 호출을 포함한 runtime 조작, 운영 DB 조회·ALTER를 수행하지 않았습니다. 따라서 아래 판정은 현재 소스·DDL·H2·단위/통합 테스트 기준이며 실제 MySQL 거래량 A/B 승인과 구분합니다.

직접 실행 경계에서 다음 공백을 최소 수정했습니다.

1. 수동 월급 launcher는 자동 지급 OFF와 비정규장만 확인해 T일 18시 직후에도 직접 실행될 수 있었습니다. 이제 활성 거래일이 일치하고, 시뮬레이션 시각이 T+1 00:00 이상이며, 해당 full-market cycle이 존재하고 `PORTFOLIO_SETTLED` 이상일 때만 Spring Batch Job을 시작합니다. 그 전에는 처리 건수 0의 완료가 아니라 실행 예정 시각을 가진 `SKIPPED`로 반환하고 JobRepository·지급 원장을 건드리지 않습니다.
2. 기본 coordinator 모드에서도 내부 `market-data/refresh`와 `corporate-actions/run` 호환 API가 독립 Job을 직접 시작할 수 있었습니다. 두 API는 기본 모드에서 제어면에서 즉시 `SKIPPED`되고, 명시적으로 coordinator를 끈 호환 모드에서만 기존 동작을 유지합니다. 호출 scheduler의 선행 분기에만 의존하던 legacy 자동 입금·일일 regime·기업행사 scheduled·빈 보유 정리 launcher도 같은 방어를 적용해 향후 호출 경로가 바뀌어도 coordinator를 우회하지 못하게 했습니다.
3. 프로필 큐 직접 정합화 API는 닫힌 시장에서도 호출되어 PRE_OPEN strict Redis exact-replace와 경합할 수 있었습니다. 기본 coordinator 모드에서는 정규장이고 주문장 시장이 실제 `OPEN`일 때만 기존 bounded ready-queue 복구를 허용하며, 닫힌 시장에서는 coordinator가 현재 phase를 소유하므로 실행 전에 `SKIPPED`합니다. 정규장 10분 복구는 작은 config·schedule 제어 테이블과 Redis만 사용하고 `stock_order`, `stock_execution`, Spring Batch metadata를 사용하지 않는 기존 설계를 유지합니다.
4. 장애 복구용 `ORDER_ENTRY_CLOSED`, `EXECUTION_DRAINED` cycle이 enum·DDL에는 남아 있지만 close service에서 완료 취급되어 영구 대기할 수 있던 공백은 두 phase도 현재 phase 그대로 claim해 기존 bounded checkpoint를 재개하도록 수정했습니다. 정상 `CLOSE_REQUESTED` 경로의 SQL 수와 transaction 경계는 변하지 않습니다.
5. PREOPEN 선택 작업 cutoff는 phase 진입 시각에만 적용되어 metadata 보존이 05:00을 넘겨도 빈 보유 정리가 뒤늦게 시작될 수 있었습니다. metadata 보존 직후 권위 시뮬레이션 시각을 다시 읽고 cutoff 이상이면 cleanup을 시작하지 않은 채 필수 security transform으로 진행합니다. metadata 보존이 shutdown·admission 경합으로 `SKIPPED`된 경우에도 다음 선택 작업을 새로 시작하지 않고 phase를 다음 poll에 재판정합니다. 추가 시각 조회는 닫힌 시장의 선택 작업 사이에서 최대 한 번이며 정규장 경로에는 없습니다.

체결 커밋 이후 파생 처리도 다시 확인했습니다. Redis 가격 발행은 `StockPriceRedisPublisher.publish()`가 직렬화와 Redis runtime 예외를 내부에서 흡수하므로, Redis 장애가 뒤의 계좌 일일요약 누적을 건너뛰게 만들지 않습니다. 일일요약은 after-commit에서 DB를 쓰지 않고 고유 `(거래일, 계좌)` 슬롯에 병합하며, 슬롯 상한이나 flush 장애가 있어도 원본 체결을 실패시키지 않고 야간 REPORTS 원본 범위 재구축으로 복구합니다. 이 경로에 방어용 wrapper나 추가 동기 DB 쓰기를 더하지 않았습니다.

정규장 거래량 영향은 다음과 같이 판정합니다.

- 이번 5개 보완은 수동·호환·장애 복구·PREOPEN 제어면 분기만 변경하므로 사용자 주문, 주문 정정, 자동 주문, 상장주관사 주문, 체결 transaction의 SQL·인덱스·잠금 행·commit 수 증가가 0입니다.
- 전체 리팩터링의 정규장 hot path 추가 비용은 거래 transaction마다 해당 종목 fence PK를 공유 잠금하는 1회 조회입니다. 전역 clock/business-state/cycle 행을 잠그지 않고, EOD snapshot·attempt·readiness 테이블도 읽지 않습니다.
- 자동 주문은 기본 25명 bounded 청크의 계좌·실제 보유 복합키 set lock, CASE 예약 UPDATE, 최대 800행 multi-row INSERT를 유지합니다. 체결은 lock-free 단일 후보 선택 뒤 계좌 정렬, 실제 보유, 정확한 주문 PK 정렬 잠금만 사용합니다. 두 경로 모두 신규 hot-ledger index와 추가 per-fill commit이 없습니다.
- EOD freeze는 실제 시장이 닫힌 뒤 bounded 주문 cohort를 캡처·취소하고, 정산은 `settlement_eligible_at` 이후 frozen snapshot만 읽습니다. 00시 이후 현금·기업행사·보고서, 04:30 이후 수량·가격 변환, 05:30 이후 가격·자동시장·readiness 순서를 coordinator phase와 공통 heavy admission으로 직렬화합니다.
- 05:00 이후에는 metadata 보존과 빈 보유 정리 같은 선택 작업을 새로 시작하지 않아 필수 PRE_OPEN 변환·readiness 여유시간을 보호합니다. 시장이 열려 있으면 coordinator와 정산 prefix 모두 무거운 업무 Job 전에 반환합니다.

불필요 코드 관점에서는 coordinator OFF fallback을 운영 ALTER·shadow·MySQL A/B 전 rollback 경로로 유지했습니다. 기본 모드에서 실행을 막기 위해 새 scheduler나 중복 service를 만들지 않고 기존 launcher 경계에만 guard를 추가했습니다. 테스트 전용으로만 사용되던 기업행사 convenience method 두 개는 제거했고 테스트가 실제 Step 진입점을 사용하도록 바꿨습니다. 호출처가 전혀 없던 no-arg PREOPEN 프로필 큐 launcher도 제거하고 cycle ID를 요구하는 coordinator 진입점만 유지했습니다. 현재 미추적 production Java 타입은 모두 다른 production 또는 test 소스에서 참조되며 단순 고아 타입은 발견되지 않았습니다. 다만 이 참조 감사는 Git 전달 완료를 뜻하지 않으며, 핵심 신규 fence·cycle·snapshot·coordinator·EOD API/UI·계획 문서가 미추적 상태인 한 커밋 또는 배포 대상에서 누락될 수 있습니다.

2026-07-17 정적 회귀 결과는 다음과 같습니다.

- `./gradlew :stock-back-service:test :stock-batch-service:test --rerun-tasks`: back 490건, batch 858건, 실패·오류·skip 0
- `npm run lint`, `npm run typecheck`, `npm run build`: 통과
- `npm run verify:contract`, `verify:navigation`, `verify:auth`, `verify:corporate-actions`: 통과
- 운영 EOD ALTER 11개 back/batch byte 동일, 모든 ALTER 첫 실행문 `USE STOCK_SERVICE;`, hot-ledger 신규 인덱스 금지 계약: 통과
- 루트 집계 저장소의 문서 index·프론트 계약 스크립트와 네 서비스 저장소 tracked diff, 모든 실제 신규 파일 trailing-whitespace 검사: 통과. 루트가 독립 하위 저장소 디렉터리를 전부 untracked로 표시하는 값은 변경 건수로 사용하지 않음

Docker가 없는 현재 환경에서는 명시적 `mysqlTest`를 통과시키지 못하므로, 위 결과만으로 “주문·체결 지연 0”이나 “계획 전체 운영 완료”라고 판정하지 않습니다. 운영 ALTER와 clean build 배포 이후 복제 MySQL에서 동일 데이터·동시성 A/B, 첫 cycle frozen settlement shadow, phase별 강제 종료 복구, 18시 fence 경합, 실제 00시/04:30/05:30 시간대 관찰을 통과해야 최종 승인합니다. 특히 현재 신규 파일의 Git 미추적 상태와 DB 전체 부하 신호 기반 admission·상세 snapshot archive 정책은 여전히 남은 배포/정책 게이트입니다.

---

## 최종 판정

이 프로젝트에 필요한 것은 “장마감 배치 시간을 조금 조정하는 것”이 아닙니다.

정확한 목표는 다음입니다.

> 18시에 거래 원장을 동결하고, 불변 스냅샷을 기준으로 정산하며, 거래일별 coordinator가 기존 Spring Batch Job/Step을 시간대별로 재시작 가능하게 통제하는 구조.

우선순위는 다음으로 확정하는 것이 가장 안전합니다.

1. 거래 session fence와 수동 마감 방어
2. cycle/attempt 유일성
3. 장마감 불변 스냅샷
4. snapshot 기반 포트폴리오 정산
5. 시간대별 coordinator
6. 기업행사·현금흐름 phase 분리
7. signal·집계·메타데이터 최적화
8. 관리자 운영 UI

2026-07-15 진단 당시 최신 정산 보유지표가 DB에서 `NULL`이었고 활성 프로세스에서 30초 커밋 타임아웃이 관찰됐습니다. 2026-07-16 최신 10개 정산일 1,020행에서는 세 보유지표의 `NULL`이 0건으로 확인되어 그 증상은 현재 재현되지 않습니다. 다만 현재 소스의 build/schema identity와 시작 readiness는 실행 중 산출물에 없고 운영 ALTER도 미적용이므로 새 EOD 구조가 검증됐다는 뜻은 아닙니다. 사용자가 서버 종료를 확인한 유지보수 창에서 실행 산출물·스키마 버전·실제 DB 값을 다시 대조하고 MySQL A/B를 통과해야 최종 승인할 수 있습니다.

### 17.6 2026-07-19 운영 ALTER 적용과 비파괴 애플리케이션 롤백

사용자가 stock-back·stock-batch 종료를 확인한 뒤 유지보수 창을 다시 검증했습니다.

- 20480·20481 리스너 0
- `stock_simulation_clock.running=0`, 마지막 heartbeat `2026-07-19 08:46:16`
- 다른 InnoDB 업무 트랜잭션 0
- `STARTING`·`STARTED`·`STOPPING` Batch Job 0
- 열린 주문장·가상가격 시장 0
- `PENDING`·`PROCESSING` signal 0
- 적용 직전 `stock_order` 1,853,972행, `stock_execution` 1,331,656행

적용 전에 다음 백업을 생성하고 SHA-256을 확인했습니다.

- `stock-batch-service/build/db-backups/2026-07-19-pre-eod-v1/STOCK_SERVICE-schema.sql`: `55a91d355307cba8aa9e109fa56396309b1ab68b652f280bdf8d437cffce8140`
- `stock-batch-service/build/db-backups/2026-07-19-pre-eod-v1/STOCK_SERVICE-eod-affected-data.sql`: `3147b0e8a3c5c8ad28618f9d7a2431a825d9bc4282f4b5ec19380d6304cdf3c6`

정방향 11개 ALTER는 지정 순서대로 모두 성공했습니다. 파일별 현실 실행시간은 다음과 같습니다.

| ALTER | 실행시간 |
|---|---:|
| session fence | 4초 |
| cycle·attempt | 8초 |
| immutable snapshot | 95초 |
| report participant snapshot | 1초 |
| signal lease | 18초 |
| corporate-action processing | 3초 |
| corporate-action chunk index | 2초 |
| daily-account last execution | 2초 |
| execution profit summary | 33초 |
| bounded EOD indexes | 12초 |
| recurring cash run cursor | 2초 |
| 합계 | 180초 |

가장 긴 95초는 기존 보유 snapshot 36,313행의 cycle 귀속 보정과 신규 소형 EOD 테이블 DDL이고, 33초는 체결 1,331,656행을 한 번 읽는 계좌·거래일 손익 요약 backfill입니다. 두 작업 모두 서버 종료 유지보수 창에서만 수행했습니다. 정방향 파일은 `stock_order`·`stock_execution`에 ALTER나 신규 인덱스를 추가하지 않았고, 적용 전후 두 hot-ledger의 `SHOW CREATE TABLE` SHA-256 비교도 각각 동일했습니다.

적용 후 대사는 다음을 통과했습니다.

- schema readiness 대상 테이블 17/17, 인덱스 13/13
- 신규 EOD 핵심 테이블 12/12
- session fence 3행, 모두 `CLOSED`, epoch 1
- 논리 cycle 164행, `(business_date, scope_type, scope_key)` 중복 0
- 기존 holding snapshot의 매핑 가능한 cycle 귀속 누락 0
- signal 신규 컬럼 10/10, `next_attempt_at` NULL 0, 열린 signal 0
- portfolio EOD 귀속·검증 컬럼 6/6
- 체결 손익 요약 컬럼 7/7, `gross = buy_gross + sell_gross` 위반 0
- 요약 `execution_count` 합계 1,331,656 = 체결 원장 행 수 1,331,656
- 요약 gross 합계 `114970874654788.00` = 체결 원장 gross 합계 동일
- 적용 후 주문 1,853,972행, 체결 1,331,656행으로 적용 직전과 동일
- 적용 종료 후 다른 활성 InnoDB 업무 트랜잭션 0

롤백은 `stock_eod_application_rollback_alter.sql`로 back·batch에 동일하게 추가했습니다. 이것은 **스키마 삭제 롤백이 아니라 구버전 애플리케이션 호환 롤백**입니다.

1. 신규 EOD 테이블·컬럼·인덱스·감사 데이터는 보존한다.
2. 구버전 stock-back의 기존 컬럼 전용 signal INSERT가 동작하도록 `next_attempt_at`을 nullable로 바꾼다.
3. 구버전 batch가 이해하지 못하는 `DEFERRED`·`DEAD_LETTER`·`PROCESSING`과 `eligible_at`을 가진 신규 `PENDING`은 자동 재실행하지 않고 `FAILED`로 종결한다.
4. status check를 구버전의 `PENDING`·`PROCESSING`·`COMPLETED`·`FAILED` 네 값으로 복원한다.
5. 정방향 11개 ALTER를 다시 적용하면 NULL backfill, NOT NULL, 신규 status domain이 복구된다.

운영 적용 전에 별도 `STOCK_EOD_ROLLBACK_TEST_20260719` 스키마에서 `운영 이전 schema dump 복원 → 정방향 11개 → 신규 signal 세 상태 삽입 → 롤백 2회 → 구버전 컬럼 전용 INSERT → 정방향 11개 재적용`을 실제 MySQL 8.0.32로 검증했습니다. 신규 signal 3건은 fail-closed, 구버전 INSERT는 `PENDING`으로 성공, 정방향 재적용 후 `next_attempt_at` backfill과 NOT NULL 복구가 확인됐습니다. 왕복 전후 hot-ledger DDL은 동일했고 임시 스키마는 검증 후 삭제했습니다.

MySQL은 개별 InnoDB DDL 문장은 원자적으로 처리하지만 여러 ALTER 파일 전체를 하나의 rollback 가능한 트랜잭션으로 묶지는 않습니다. 따라서 실행 실패 시 뒤 파일을 자동 진행하거나 신규 객체를 즉시 DROP하지 않고, 마지막 성공 지점을 확인한 뒤 멱등 정방향 재실행 또는 호환 롤백을 선택합니다. 정확한 이전 스키마와 영향 데이터로 돌아가야 할 때만 위 사전 dump를 사용합니다.

이 시점의 판정은 **운영 스키마 적용·정적 hot-ledger 비변경·backfill 대사 통과**입니다. 서비스가 아직 정지돼 있으므로 주문 TPS, 주문 API p95, 체결 반응 p95/p99, commit·row-lock·deadlock의 재기동 후 A/B는 아직 통과로 표시하지 않습니다. 사용자가 새 산출물로 stock-back과 stock-batch를 재시작한 뒤 startup readiness, 첫 cycle shadow, 정규장 거래량 비회귀를 이어서 확인해야 최종 운영 승인할 수 있습니다.

### 17.7 2026-07-19 수동 시간 경계와 활성 거래일 정합성 보강

관리자 수동 시간 제어 세 동작을 EOD 작업 실행 명령이 아닌 시간대 진입 게이트로 다시 고정했습니다.

- `TODAY_MARKET_CLOSE`: 오늘 18:00 경계로 시계만 이동
- `NEXT_SIMULATION_DAY_START`: 동결·정산 완료 후 다음 일자 00:00으로 이동
- `NEXT_MARKET_OPEN`: 야간 suffix와 readiness 완료 후 다음 장 06:00으로 이동

기존 판정은 AFTER_CLOSE에는 원시 날짜, PRE_OPEN에는 원시 날짜의 전일을 cycle 날짜로 추정했습니다. 원시 시간이 여러 날짜 앞서고 중간 SKIPPED cycle이 완료된 복구 상황에서는 실제 `active_business_date`와 다른 날짜의 완료 상태를 읽을 수 있었습니다. 이를 다음처럼 수정했습니다.

1. `stock_market_business_state.active_business_date`를 판정 cycle의 권위 날짜로 사용
2. PRE_OPEN 개장은 `preparing_business_date = active_business_date + 1일 = raw_simulation_date`까지 요구
3. 서버가 계산한 `availableJumpActions`를 GET 응답과 PATCH 검증에서 공통 사용
4. 프론트는 이 목록만 사용해 버튼을 활성화하고 원시 일자·활성 거래일·다음 준비 거래일을 분리 표시
5. 현재 활성 계좌·portfolio·주문·체결을 다시 세지 않으며, 1초 polling은 clock singleton 한 번과 business-state/cycle/metric bounded 조회 한 번으로 제한
6. GET polling에는 Spring transaction을 새로 열지 않아 불필요한 COMMIT을 만들지 않음

실제 MySQL 8.0.32에서 control 조회를 `EXPLAIN ANALYZE`한 결과 enabled instrument 확인은 covering index 1행 lookup으로 약 0.025ms였고 나머지 singleton/unique join은 실행 전 constant row로 해석됐습니다. 조회 당시 `active_business_date=2026-12-11`, `raw_simulation_date=2026-12-11`, `preparing_business_date=NULL`이었으며 활성 거래일 cycle은 아직 생성되지 않은 정규장 상태였습니다. 이 확인은 SELECT만 사용했습니다.

회귀 검증은 `SimulationClockServiceTest`의 정상 세 경계, 정산만 완료된 상태, PRE_OPEN 준비 완료, 원시 시간이 활성 거래일보다 앞선 REGULAR/AFTER_CLOSE/PRE_OPEN 차단을 포함합니다. stock-back 전체 테스트, stock-front lint/build/contract가 통과했습니다.

다만 10:20 점검에서 사용자가 종료했다고 본 상태와 달리 IntelliJ stock-back PID 44617과 stock-batch PID 44649가 각각 20480·20481에서 09:32부터 실행 중이었고 heartbeat도 갱신 중이었습니다. 실행 class 시각은 09:32, 수정 source 시각은 10:22이므로 이 인스턴스에는 본 변경이 반영되지 않았습니다. 에이전트는 이 기존 프로세스를 종료하거나 재시작하지 않았습니다. 사용자가 두 프로세스를 새 산출물로 재시작한 뒤에만 실제 API 응답과 세 경계 전이를 운영 검증합니다.

사용자가 10:26에 새 산출물로 재시작한 뒤 첫 실제 경계 검증에서는 다음을 확인했습니다.

- 정규장 `2026-12-11`의 GET 응답은 원시 일자와 활성 거래일이 일치하고 `TODAY_MARKET_CLOSE`만 허용했다.
- 10:28:56의 `TODAY_MARKET_CLOSE` PATCH는 약 0.8초에 HTTP 200으로 끝났고, 응답 즉시 시각은 18:00, 세션은 `AFTER_CLOSE`, 다음 이동 목록은 비어 있었다.
- cycle 263은 close run 199로 `LEDGER_FROZEN`까지 진행했다. 주문 169건을 캡처해 169건 모두 취소했고, 대상 계좌 102개·계좌 snapshot 105개·보유 snapshot 215개·가격 snapshot 3개를 기록했으며 reconciliation mismatch는 0건이었다.
- 세 종목 fence는 모두 거래일 `2026-12-11`, epoch 5, `CLOSED`가 되어 원장 동결 이후 주문·체결 재개를 막았다.
- 전이 전 체결 worker failure, 자동 주문 deadlock retry, 자동 주문 insert failure는 모두 0건이었다. 원장 동결 중 이 세 카운터의 실패 증가도 없었다.

다만 18:10 이후 첫 frozen-snapshot 정산에서 MySQL 전용 결과 컬럼 계약 오류를 발견했습니다. `AccountSettlementTargetReader`가 `account_id AS id`를 SELECT하면서 paging sort key는 `account_id`로 지정해, Spring Batch가 MySQL `ResultSet`에서 sort key를 추출할 때 `Column 'account_id' not found`로 실패했습니다. H2는 alias와 원본 컬럼명을 모두 허용해 기존 다중 페이지 테스트가 이 차이를 잡지 못했습니다.

수정은 hot path나 스키마를 늘리지 않고 다음 두 줄의 계약 통일과 회귀 테스트로 제한했습니다.

- SELECT 결과를 실제 `account_id` 컬럼명으로 유지
- 업무 row mapper와 paging sort key가 모두 `account_id`를 사용
- 생성된 첫 페이지 SQL이 물리적 sort key 컬럼을 SELECT하는 계약 테스트 추가

집중 reader 테스트와 `:stock-batch-service:test --rerun-tasks` 전체가 통과했습니다. 실패 cycle은 `LEDGER_FROZEN/FAILED`에 남아 다음 일자 이동을 차단했고 snapshot과 취소 반환을 다시 만들지 않습니다. 반복 정산 실패가 JobRepository·원격 DB 부하로 이어지지 않도록 배치 PID 55133만 종료했으며 백엔드는 상태 조회를 위해 유지했습니다. 이 수정에는 DB ALTER가 필요하지 않습니다. 사용자가 수정 산출물로 배치를 다시 시작하면 동일 cycle 263의 settlement Step부터 재개한 뒤 00:00·04:30·05:30·06:00 전이와 거래량 비회귀 관찰을 이어서 완료해야 합니다.
