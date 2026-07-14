# Java 21 Batch Project Standard

<!-- Updated: 2026-06-23 -->

## Purpose

이 문서는 Java 21 기반 신규 배치 프로젝트를 처음 구성할 때 적용할 표준이다.

목표는 단순히 `job / step / reader / processor / writer` 폴더를 만드는 것이 아니다. 운영 환경에서 실패해도 재시작할 수 있고, 같은 데이터를 중복 처리하지 않으며, 실행 상태와 실패 원인을 추적할 수 있는 배치 구조를 만드는 것이다.

이 문서는 다음 범위를 다룬다.

- version policy
- batch architecture
- package structure
- database architecture and initial schema
- job / step / reader / processor / writer 책임
- job parameter and restart policy
- transaction / retry / skip policy
- idempotency and duplicate prevention
- scaling policy
- observability
- testing standard
- implementation templates
- anti-patterns

## Version Policy

### Default Recommendation

신규 프로젝트의 기본 권장 조합은 다음이다.

- Java: 21 LTS
- Spring Boot: latest stable 4.x line
- Spring Batch: latest stable 6.x line
- Build: Gradle 8.14+ or Gradle 9.x
- Test: JUnit Jupiter

Spring Batch 6.x는 Spring Framework 7.x 기반이며, Batch 6 문서 기준으로 다음 변화가 중요하다.

- batch infrastructure configuration improvements
- new chunk-oriented processing model
- new concurrency model
- graceful shutdown support
- JFR observability support
- local chunking support
- remote step support
- lambda style configuration
- XML namespace deprecation/pruning

운영 안정성을 더 보수적으로 잡아야 하거나 Spring Boot 3.x 생태계에 묶인 프로젝트라면 다음 호환 트랙을 쓴다.

- Java: 21 LTS
- Spring Boot: latest stable 3.x line
- Spring Batch: latest stable 5.2.x line

프로젝트를 처음 만들 때는 반드시 `README.md` 또는 `docs/architecture.md`에 실제 선택한 트랙을 명시한다.

### Current Stock Batch Track

현 `stock-batch-service`의 선택 트랙은 다음과 같다.

- Java: 21 LTS
- Spring Boot: 4.x line
- Spring Batch: 6.x line with JDBC `JobRepository`
- Build: Gradle 9.x wrapper
- Test: JUnit Jupiter
- Batch execution: `@Scheduled` + internal job API + `StockBatchJobLauncher` 기반 워커
- Batch metadata: 별도 `STOCK_BATCH_METADATA` schema와 `BATCH_*` tables

현재 업무 배치는 실제 Spring Batch 6 `Job`/`Step`으로 실행한다. `StockBatchJobRunner`는 `JobOperator` 호출, 업무 DB 분산 lock, 종료 대기와 API 응답 변환만 담당하고, `JobExecution`/`StepExecution` 생성·count·재시작 판정은 `JobRepository`가 담당한다.

native Job 경계는 다음과 같다.

| Job | Step | 형태 | 재시작 단위 |
| --- | --- | --- | --- |
| `market-close-rollover` | `market-close-snapshot-step` | Tasklet | 영업일·operation·symbol/신호 |
| `portfolio-settlement` | `portfolio-settlement-step` | paging chunk | 영업일·snapshot 시각·강제 실행 version |
| `corporate-actions` | `apply-due-corporate-actions-step` | Tasklet | 시뮬레이션 분 단위 sweep |
| `auto-participant-cash-flow` | `auto-participant-cash-flow-step` | Tasklet | 시뮬레이션 분 또는 수동 signal |
| `auto-market-daily-regime-pre-create` | `auto-market-daily-regime-pre-create-step` | Tasklet | 영업일 |

포트폴리오 정산은 처리 대상이 많고 계좌별 결과가 독립적이므로 `JdbcPagingItemReader -> ItemProcessor -> ItemWriter`와 configurable chunk transaction을 사용한다. 다른 네 Job은 기존 command service가 자체 업무 트랜잭션 또는 이벤트별 독립 트랜잭션을 소유하므로 억지로 가짜 Reader/Writer로 나누지 않고 의미 있는 Tasklet Step 하나로 표현한다.

주문 체결, 자동 주문 생성, 주문 만료, 상장주관사 유동성 공급, 프로필 큐 정합화 등 초단위 micro 작업은 `LightweightBatchTask` 경계로 실행한다. 이 작업들은 재시작 checkpoint보다 낮은 지연과 작은 metadata가 중요하므로 `BATCH_*` row를 만들지 않고 Micrometer와 업무 원장을 관측 기준으로 사용한다.

프로세스 crash로 `STARTING`/`STARTED`/`STOPPING` execution이 남은 경우에는 다음 실행 시 해당 job의 business lock을 먼저 획득한다. lock 소유권이 확인된 뒤에만 그 job의 open execution과 open step을 `FAILED`로 닫고 같은 identifying parameter로 재시작한다. 이 순서는 다중 노드에서 아직 실행 중인 다른 job을 시작 시각만 보고 종료시키는 전역 metadata sweep을 피한다.

## Batch Architecture

Spring Batch는 스케줄러가 아니다. Spring Batch는 job 실행, step 실행, 상태 저장, 재시작, 트랜잭션, retry/skip, 메타데이터를 담당한다.

스케줄링과 batch execution은 분리한다.

```text
Scheduler / API / Message Trigger
  -> JobLauncher or JobOperator
  -> Job
  -> Step
  -> ItemReader
  -> ItemProcessor
  -> ItemWriter
  -> JobRepository metadata
  -> Metrics / Logs / Alerts
```

권장 원칙:

- scheduler는 언제 실행할지만 결정한다.
- launcher는 어떤 job을 어떤 parameter로 실행할지만 결정한다.
- job은 step 흐름만 정의한다.
- step은 reader / processor / writer 조립과 실행 정책만 정의한다.
- reader는 읽기만 한다.
- processor는 변환과 필터만 한다.
- writer는 한 종류의 반영만 한다.

## Package Standard

신규 Spring Batch 구조는 도메인 중심 수직 구조를 기본으로 한다.

```text
src/main/java/<base-package>/
  batch/
    config/
      BatchInfrastructureConfig.java
      BatchJobLauncherConfig.java
      BatchObservabilityConfig.java
    common/
      listener/
      parameter/
      policy/
      support/
    <domain>/
      job/
      step/
      reader/
      processor/
      writer/
      model/
      support/
```

예:

```text
batch/
  settlement/
    job/
      SettlementDailyJobConfig.java
    step/
      SettlementLoadStepConfig.java
      SettlementSendStepConfig.java
    reader/
      SettlementTargetReader.java
    processor/
      SettlementPayloadProcessor.java
    writer/
      SettlementOutboxWriter.java
      SettlementStatusWriter.java
    model/
      SettlementTarget.java
      SettlementPayload.java
    support/
      SettlementQueryService.java
      SettlementMessageRenderer.java
```

전역 `job/`, `step/`, `reader/`, `processor/`, `writer/` 폴더에 모든 도메인을 섞지 않는다. 프로젝트가 커지면 한 job의 전체 흐름을 추적하기 어려워진다.

## Naming Standard

Job:

- class: `<Domain><UseCase>JobConfig`
- examples: `SettlementDailyJobConfig`, `NewsCollectJobConfig`, `OrderExpireJobConfig`
- bean name은 운영자가 볼 수 있는 이름으로 만든다.

```java
public static final String JOB_NAME = "settlementDailyJob";
```

Step:

- class: `<Domain><Action>StepConfig`
- examples: `SettlementLoadStepConfig`, `SettlementSendStepConfig`, `OrderExpireStepConfig`
- step 이름은 job 안에서 실패 위치를 바로 알 수 있어야 한다.

```java
public static final String STEP_NAME = "settlementLoadStep";
```

Reader / Processor / Writer:

- `<Domain><Purpose>Reader`
- `<Domain><Purpose>Processor`
- `<Domain><Purpose>Writer`

Examples:

- `SettlementTargetReader`
- `SettlementPayloadProcessor`
- `SettlementOutboxWriter`

## Job Standard

Job은 흐름만 가진다.

허용:

- step 순서 정의
- flow 분기
- listener 연결
- restartable 여부 정의
- job 이름 정의

금지:

- repository 호출
- 외부 API 호출
- entity 생성
- 메시지 조립
- business calculation
- transaction 직접 처리

Template:

```java
@Configuration
public class SettlementDailyJobConfig {

    public static final String JOB_NAME = "settlementDailyJob";

    @Bean(name = JOB_NAME)
    public Job settlementDailyJob(
            JobRepository jobRepository,
            @Qualifier(SettlementLoadStepConfig.STEP_NAME) Step loadStep,
            @Qualifier(SettlementSendStepConfig.STEP_NAME) Step sendStep
    ) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(loadStep)
                .next(sendStep)
                .build();
    }
}
```

Job 분리 기준:

- 스케줄이 다르면 job을 나눈다.
- 재시작 기준이 다르면 job을 나눈다.
- 실패 알림 기준이 다르면 job을 나눈다.
- 트랜잭션 성격이 다르면 job을 나눈다.
- 외부 시스템 의존성이 다르면 job을 나눈다.

## Job Parameter Standard

Job parameter는 재실행과 중복 실행을 결정하는 핵심 계약이다.

Required:

- `businessDate`
- `requestId`
- `triggeredBy`

Optional:

- `dryRun`
- `force`
- `partitionKey`
- `traceId`

Identifying:

- `businessDate`
- `jobMode`
- 업무 결과 범위를 바꾸는 `operation`, `symbol`, `snapshotAt`, `sweepAt`, `signalId`
- 운영자가 명시적으로 동일 범위를 다시 계산할 때만 사용하는 양의 `runVersion`

Non-identifying:

- `requestId`
- `triggeredAt`
- `traceId`

원칙:

- `businessDate`는 업무 기준일이다.
- `requestId`는 호출 추적용이다.
- `triggeredAt`은 매번 달라도 같은 업무 실행을 새 `JobInstance`로 만들면 안 된다.
- 재시작해야 하는 job은 identifying parameter를 안정적으로 유지한다.
- 강제 재실행이 필요한 경우 `force=true`만으로 처리하지 말고 별도 `jobMode` 또는 operator action을 기록한다.
- 현재 구현은 임의 `runId`를 사용하지 않는다. 실패 재시도는 같은 identifying parameter로 같은 `JobInstance`의 새 `JobExecution`을 만들고, 완료된 instance는 다시 실행하지 않는다.

권장 model:

```java
public record BatchRunParameter(
        LocalDate businessDate,
        String requestId,
        String triggeredBy,
        boolean dryRun,
        boolean force,
        String traceId
) {
}
```

## Step Standard

Step은 reader / processor / writer를 조립하고 실행 정책을 정한다.

허용:

- chunk size 지정
- transaction manager 지정
- reader / processor / writer 연결
- retry / skip / listener 설정
- task executor or partition policy 연결

금지:

- repository 직접 호출
- 외부 API 호출
- entity 생성
- 메시지 포맷팅
- business rule 구현

Template:

```java
@Configuration
public class SettlementLoadStepConfig {

    public static final String STEP_NAME = "settlementLoadStep";
    private static final int CHUNK_SIZE = 500;

    @Bean(name = STEP_NAME)
    @JobScope
    public Step settlementLoadStep(
            JobRepository jobRepository,
            @Qualifier("batchTransactionManager") PlatformTransactionManager txManager,
            @Qualifier(SettlementTargetReader.BEAN_NAME) ItemReader<SettlementTarget> reader,
            @Qualifier(SettlementPayloadProcessor.BEAN_NAME) ItemProcessor<SettlementTarget, SettlementPayload> processor,
            @Qualifier(SettlementOutboxWriter.BEAN_NAME) ItemWriter<SettlementPayload> writer
    ) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<SettlementTarget, SettlementPayload>chunk(CHUNK_SIZE, txManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .faultTolerant()
                .retry(TransientDataAccessException.class)
                .retryLimit(3)
                .skip(ValidationException.class)
                .skipLimit(100)
                .build();
    }
}
```

Step 설계 기준:

- chunk step과 tasklet step을 명확히 구분한다.
- 대량 데이터 처리는 chunk step을 기본으로 한다.
- 단발성 command, cleanup, lock acquisition은 tasklet step을 허용한다.
- chunk size는 상수화하고 근거를 남긴다.
- retry/skip은 주석이 아니라 설정으로 표현한다.
- listener는 logging, metrics, audit 용도로 제한한다.

## Reader Standard

Reader는 읽기만 한다.

허용:

- DB 조회
- 파일 읽기
- queue/topic에서 수신
- 이미 스냅샷된 외부 데이터 읽기

금지:

- update/delete/insert
- 외부 API 호출 후 상태 변경
- 메시지 전송
- 비즈니스 계산
- 중복 처리 완료 표시

Reader 선택 기준:

- small in-memory data: `ListItemReader`
- flat file: `FlatFileItemReader`
- relational database, moderate size: `JdbcPagingItemReader` or `JpaPagingItemReader` with stable sort
- relational database, large size: keyset/cursor reader preferred
- external API: fetch snapshot first, then read snapshot table/file

대량 DB reader 규칙:

- 안정 정렬이 없는 paging 금지
- offset paging은 데이터 변경이 없는 작은 범위에서만 허용
- 대량 테이블은 keyset 또는 cursor 우선
- reader query는 처리 대상만 읽어야 한다.
- 재시작 가능 reader는 `ItemStream` 상태 저장을 검토한다.

권장 패턴:

```java
@Component(SettlementTargetReader.BEAN_NAME)
@StepScope
public class SettlementTargetReader implements ItemReader<SettlementTarget> {

    public static final String BEAN_NAME = "settlementTargetReader";

    @Override
    public SettlementTarget read() {
        // Read only. No writes, no external side effects.
        return null;
    }
}
```

## Processor Standard

Processor는 변환과 필터만 한다.

허용:

- validation
- filtering with `null`
- DTO -> command 변환
- entity -> payload 변환
- 순수 계산

금지:

- DB 저장
- 외부 API 호출
- 메시지 전송
- 상태 변경
- 시간/랜덤값을 직접 생성해서 결과를 흔드는 코드

원칙:

- 가능하면 순수 함수처럼 작성한다.
- 복잡한 정책은 `support` 패키지로 뺀다.
- `null` 반환 필터는 의도가 이름으로 드러나야 한다.
- validation 실패를 skip할지 fail할지 step policy와 맞춘다.

Template:

```java
@Component(SettlementPayloadProcessor.BEAN_NAME)
public class SettlementPayloadProcessor implements ItemProcessor<SettlementTarget, SettlementPayload> {

    public static final String BEAN_NAME = "settlementPayloadProcessor";

    @Override
    public SettlementPayload process(SettlementTarget item) {
        if (!item.isProcessable()) {
            return null;
        }
        return SettlementPayload.from(item);
    }
}
```

## Writer Standard

Writer는 한 종류의 반영만 한다.

허용:

- DB insert/update/delete
- outbox insert
- file write
- message publish
- external API call

금지:

- 한 writer에서 외부 전송과 DB 상태 변경을 동시에 수행
- 한 writer에서 insert 후 바로 delete까지 수행
- retry 가능하지 않은 외부 호출을 멱등성 없이 수행
- item별 개별 commit 흉내

Writer 분리 기준:

- DB 저장: `EntityWriter`
- 외부 전송: `SenderWriter`
- 상태 변경: `StatusWriter`
- 삭제: `DeleteWriter`

외부 I/O가 있는 경우 권장 구조:

```text
Step 1: target read -> payload process -> outbox write
Step 2: outbox read -> external send writer
Step 3: sent outbox read -> status update writer
```

이 구조가 단순 writer 하나보다 긴 이유:

- 전송 실패 시 재시작 지점이 명확하다.
- 중복 전송 방지 키를 둘 수 있다.
- DB 상태 변경과 외부 side effect를 분리할 수 있다.

Template:

```java
@Component(SettlementOutboxWriter.BEAN_NAME)
public class SettlementOutboxWriter implements ItemWriter<SettlementPayload> {

    public static final String BEAN_NAME = "settlementOutboxWriter";

    @Override
    public void write(Chunk<? extends SettlementPayload> chunk) {
        // One side effect only: persist outbox rows.
    }
}
```

## Database Architecture Standard

배치 프로젝트는 처음부터 DB 역할을 분리해서 설계한다. Spring Batch metadata table만 있으면 배치 DB 설계가 끝난 것이 아니다.

권장 DB 영역:

- batch metadata DB: Spring Batch `JobRepository` tables
- business DB: 실제 처리 대상 도메인 테이블
- staging / snapshot DB: 외부 API, 파일, 메시지 데이터를 고정 저장하는 임시/스냅샷 테이블
- outbox DB: 외부 전송, 알림, webhook, message publish 대기 테이블
- audit DB: 실행 요청, operator, `requestId`, `traceId`, 실패 사유 기록
- lock DB: 중복 실행 방지, scheduler lock, partition lock

작은 프로젝트는 물리 DB를 하나로 시작해도 된다. 하지만 schema 또는 table prefix로 역할은 반드시 구분한다.

### Batch Metadata Tables

Spring Batch JDBC `JobRepository`를 쓰는 프로젝트는 초기 schema에 다음 테이블을 포함한다.

- `BATCH_JOB_INSTANCE`
- `BATCH_JOB_EXECUTION`
- `BATCH_JOB_EXECUTION_PARAMS`
- `BATCH_STEP_EXECUTION`
- `BATCH_STEP_EXECUTION_CONTEXT`
- `BATCH_JOB_EXECUTION_CONTEXT`
- `BATCH_STEP_EXECUTION_SEQ`
- `BATCH_JOB_EXECUTION_SEQ`
- `BATCH_JOB_INSTANCE_SEQ`

역할:

- `BATCH_JOB_INSTANCE`: job 이름과 identifying `JobParameters`로 결정되는 논리적 실행 단위다. `JOB_NAME + JOB_KEY` unique constraint가 필요하다.
- `BATCH_JOB_EXECUTION`: 실제 실행 시도 기록이다. status, start/end time, exit code, exit message를 저장한다.
- `BATCH_JOB_EXECUTION_PARAMS`: 실행 parameter 저장소다. identifying 여부가 재시작/중복 실행 판단에 중요하다.
- `BATCH_STEP_EXECUTION`: step 실행 이력이다. read/write/filter/skip/rollback count를 저장한다.
- `BATCH_STEP_EXECUTION_CONTEXT`: step 재시작 상태 저장소다. reader cursor, paging state, custom execution state가 들어갈 수 있다.
- `BATCH_JOB_EXECUTION_CONTEXT`: job level execution context 저장소다.
- `BATCH_*_SEQ`: MySQL 등 sequence가 없는 DB에서 id 생성을 위한 sequence table이다.

초기 SQL 파일은 다음 기준으로 관리한다.

```text
src/main/resources/db/schema/batch-metadata-mysql.sql
src/main/resources/db/schema/batch-metadata-h2.sql
src/main/resources/db/schema/batch-domain.sql
src/main/resources/db/schema/batch-outbox.sql
src/main/resources/db/schema/batch-audit.sql
```

`CREATE TABLE`과 운영 삭제 SQL을 같은 파일에 섞지 않는다. metadata cleanup은 별도 파일이나 운영 job으로 분리한다.

```text
src/main/resources/db/maintenance/batch-metadata-retention.sql
```

### Business Tables

business table은 배치가 읽고 처리하는 실제 도메인 데이터다.

기준:

- reader query가 사용하는 where 조건에 index를 둔다.
- 상태 전이 컬럼을 명확히 둔다.
- 처리 대상 선점이 필요하면 lock owner, locked at, status를 둔다.
- 대량 update/delete는 단건 JPA save loop보다 bulk operation을 우선 검토한다.

예:

```text
status
requested_at
processed_at
locked_by
locked_at
retry_count
last_error_code
last_error_message
```

### Staging / Snapshot Tables

외부 API, 파일, 크롤링 데이터는 바로 최종 테이블에 반영하지 않는다. 먼저 snapshot으로 고정하고, 배치는 snapshot을 읽는다.

장점:

- 같은 입력으로 재실행 가능
- 외부 API 결과 변동과 배치 재시작 분리
- 장애 분석 시 원본 입력 확인 가능

권장 컬럼:

```text
id
source_type
source_key
business_date
payload
payload_hash
fetched_at
created_at
```

unique key:

```sql
unique(source_type, source_key, business_date)
```

### Outbox Tables

외부 전송이 있는 배치는 outbox table을 기본으로 검토한다.

권장 컬럼:

```text
id
event_type
idempotency_key
aggregate_type
aggregate_id
business_date
payload
status
attempt_count
next_retry_at
sent_at
last_error_code
last_error_message
created_at
updated_at
```

권장 status:

- `READY`
- `SENDING`
- `SENT`
- `FAILED`
- `DEAD`

unique key:

```sql
unique(idempotency_key)
```

outbox writer는 outbox row 생성만 담당한다. 외부 전송 writer는 outbox를 읽어 전송만 담당한다. 전송 성공 후 상태 변경은 별도 writer 또는 별도 step에서 수행한다.

### Audit Tables

batch metadata는 Spring Batch 내부 실행 상태를 잘 보여준다. 하지만 운영자가 요청 단위로 추적하려면 별도 audit table이 필요할 수 있다.

권장 컬럼:

```text
id
request_id
trace_id
job_name
job_execution_id
business_date
triggered_by
trigger_type
status
requested_at
started_at
ended_at
failure_code
failure_message
```

### Lock Tables

중복 실행을 막아야 하는 job은 lock 전략을 명시한다.

선택지:

- DB unique constraint
- scheduler lock table
- business table status 선점
- distributed lock

권장 lock 컬럼:

```text
lock_name
lock_key
locked_by
locked_until
created_at
updated_at
```

unique key:

```sql
unique(lock_name, lock_key)
```

### Retention Policy

metadata와 domain 이력은 무한 보관하지 않는다.

문서화할 항목:

- batch metadata 보관 기간
- outbox `SENT` 보관 기간
- outbox `FAILED` / `DEAD` 보관 기간
- staging payload 보관 기간
- audit 보관 기간
- 삭제 job 이름과 실행 주기

주의:

- 실행 중인 job metadata를 삭제하면 안 된다.
- `BATCH_JOB_INSTANCE`는 관련 `BATCH_JOB_EXECUTION` 정리 후 orphan만 삭제한다.
- cleanup SQL은 운영 기준일을 하드코딩하지 않는다.

## Transaction Policy

기본 원칙:

- chunk 단위 transaction을 기본으로 한다.
- writer side effect는 transaction 경계를 기준으로 설계한다.
- 외부 API 호출은 DB transaction 안에서 오래 붙잡지 않는다.
- 대량 update/delete는 bulk operation 또는 별도 tasklet을 검토한다.

DB + 외부 API가 섞일 때:

- DB transaction 안에서 외부 API를 호출하지 않는 구조가 우선이다.
- 필요한 경우 outbox pattern을 사용한다.
- 외부 API 호출 결과를 DB에 반영해야 하면 별도 step으로 분리한다.

## Retry / Skip / Fail Policy

Retry 대상:

- transient network timeout
- HTTP 429, 502, 503, 504
- database deadlock
- temporary connection failure

Skip 대상:

- 단일 row validation error
- 이미 처리된 duplicate item
- 복구 불가능하지만 전체 job을 실패시킬 필요가 없는 item-level 오류

Fail 대상:

- 필수 job parameter 누락
- schema mismatch
- 인증 실패
- 권한 실패
- writer의 멱등성 키 누락
- reader query 자체 오류

표준:

- `retryLimit`: default 3
- `skipLimit`: default 0, business-approved item validation only
- `backoff`: external API에는 exponential backoff 권장

모든 skip은 로그와 metric으로 남긴다. skip count가 운영 기준을 넘으면 job은 성공으로 끝나도 alert를 보낸다.

## Restart Policy

재시작 가능 job은 처음부터 재시작 기준을 설계한다.

필수 기준:

- identifying `JobParameters`가 안정적이어야 한다.
- reader가 재시작 위치를 복구할 수 있어야 한다.
- writer가 같은 chunk 재실행에 안전해야 한다.
- 외부 전송은 idempotency key를 가져야 한다.

재시작 불가능 job은 명시한다.

```java
return new JobBuilder(JOB_NAME, jobRepository)
        .preventRestart()
        .start(step)
        .build();
```

단, `preventRestart()`는 편의로 쓰지 않는다. 복구 전략이 명확하지 않은 위험한 job임을 문서화해야 한다.

## Idempotency Policy

배치 writer는 실패 후 같은 chunk를 다시 실행할 수 있다는 전제로 만든다.

필수 규칙:

- 업무 키를 정한다.
- outbox/event/message에는 idempotency key를 저장한다.
- 외부 전송 API가 idempotency key를 지원하면 반드시 사용한다.
- 지원하지 않으면 sent table 또는 unique constraint로 중복을 막는다.

예:

```text
idempotencyKey = jobName + ":" + businessDate + ":" + domainId + ":" + action
```

DB 저장은 unique constraint로 방어한다.

```sql
unique(job_name, business_date, domain_id, action)
```

## Scaling Policy

처음부터 병렬화하지 않는다.

확장 순서:

1. single-thread chunk step
2. chunk size tuning
3. query/index tuning
4. multi-threaded step
5. partitioning
6. local chunking
7. remote step or remote chunking

원칙:

- 측정 전 병렬화 금지
- reader/writer thread-safety 확인 전 multi-threaded step 금지
- partition key는 겹치지 않아야 한다.
- partition별 처리량과 실패율을 metric으로 분리한다.
- remote processing은 운영 복잡도를 감당할 수 있을 때만 도입한다.
- Java 21 virtual thread는 I/O-bound 작업에 유리할 수 있다. 단, DB connection pool, 외부 API rate limit, reader/writer thread-safety가 먼저 검증되어야 한다.

## Observability Standard

모든 job 실행은 추적 가능해야 한다.

로그 필수 필드:

- `jobName`
- `jobExecutionId`
- `jobInstanceId`
- `stepName`
- `stepExecutionId`
- `businessDate`
- `requestId`
- `traceId`
- `readCount`
- `filterCount`
- `processSkipCount`
- `writeCount`
- `writeSkipCount`
- `rollbackCount`
- `exitStatus`
- `failureException`

Metric 필수:

- job duration
- step duration
- read count
- write count
- skip count
- retry count
- failure count
- external API latency
- external API error count

Alert 기준:

- job failed
- job running too long
- skip count threshold exceeded
- retry count threshold exceeded
- no execution in expected schedule window
- outbox backlog threshold exceeded

Batch 6.x 사용 시 JFR observability 도입을 검토한다.

## Testing Standard

테스트는 레이어별로 나눈다.

Unit test:

- processor pure transformation
- parameter validation
- idempotency key generation
- message rendering
- retry/skip classifier

Slice test:

- reader query
- writer DB 반영
- repository constraint

Batch test:

- job launch with parameters
- restart scenario
- failed step recovery
- skip/retry behavior
- duplicate execution prevention

권장:

- `spring-batch-test`
- JUnit Jupiter
- Testcontainers for database integration

테스트 데이터는 다음 케이스를 포함한다.

- 정상 데이터
- 중복 데이터
- validation 실패 데이터
- reader 중간 실패
- writer 중간 실패
- 외부 API timeout
- 재시작 시 같은 chunk 재처리

## Configuration Standard

Spring Boot를 쓰는 경우 자동 구성을 우선한다. 직접 infrastructure bean을 만드는 것은 다음 경우로 제한한다.

- batch metadata datasource가 business datasource와 다르다.
- transaction manager를 명확히 분리해야 한다.
- `JobRepository` isolation level, table prefix, serializer를 조정해야 한다.
- multi-tenant batch metadata가 필요하다.

Batch 6.x에서는 JDBC/Mongo repository infrastructure 구성이 더 명확해졌다. JDBC `JobRepository`를 명시해야 하는 프로젝트는 dedicated batch datasource와 transaction manager를 문서화한다.

## Implementation Checklist

새 job을 추가할 때 반드시 확인한다.

- job name이 운영 목적을 드러낸다.
- identifying `JobParameters`가 명확하다.
- step이 하나의 실행 단계를 나타낸다.
- reader에 side effect가 없다.
- processor가 DB/API를 호출하지 않는다.
- writer가 한 종류의 side effect만 가진다.
- batch metadata schema가 준비되어 있다.
- business/staging/outbox/audit/lock table 필요 여부를 판단했다.
- 외부 전송이 있으면 outbox table과 idempotency unique key가 있다.
- metadata cleanup과 retention 정책이 있다.
- retry 대상과 fail 대상이 분리되어 있다.
- skip 허용 사유가 문서화되어 있다.
- writer가 재실행에 안전하다.
- 외부 전송은 idempotency key를 가진다.
- job/step metric과 log가 남는다.
- 실패 후 재시작 테스트가 있다.

## Anti-patterns

금지한다.

- `BasicReader`, `BasicProcessor`, `BasicWriter` 같은 기능 없는 타입 별칭
- reader에서 DB update
- processor에서 API 호출
- writer 하나에서 외부 전송과 DB 상태 변경을 동시에 처리
- 안정 정렬 없는 paging reader
- job parameter에 매번 현재 시각만 넣어서 재시작을 불가능하게 만드는 방식
- skip을 무제한으로 열어두는 방식
- retry를 모든 exception에 거는 방식
- 운영자가 볼 수 없는 job/step 이름
- 실패 알림 없이 로그만 남기는 방식
- 테스트 없이 batch metadata table만 보고 수동 복구하는 방식

## Minimal Greenfield Template

```text
batch/
  config/
    BatchObservabilityConfig.java
  common/
    listener/
      BatchLoggingListener.java
    parameter/
      BatchRunParameter.java
    policy/
      BatchRetryPolicy.java
  sample/
    job/
      SampleImportJobConfig.java
    step/
      SampleImportStepConfig.java
    reader/
      SampleTargetReader.java
    processor/
      SampleTargetProcessor.java
    writer/
      SampleTargetWriter.java
    model/
      SampleTarget.java
      SampleResult.java
    support/
      SampleQueryService.java
```

최소 job은 다음 순서로 만든다.

1. job parameter contract 작성
2. output side effect 정의
3. writer idempotency 설계
4. reader query 설계
5. processor transformation 작성
6. step 조립
7. job flow 작성
8. restart test 작성
9. observability 연결

## Final Rule

좋은 배치 구조는 폴더 이름으로 결정되지 않는다.

좋은 배치 구조는 다음 질문에 답할 수 있어야 한다.

- 이 job은 같은 parameter로 다시 실행하면 어떻게 되는가?
- 실패한 step은 어디서부터 재시작되는가?
- 같은 item이 두 번 write되면 어떻게 막는가?
- 외부 전송은 중복 호출되어도 안전한가?
- 운영자는 어느 `jobExecutionId`에서 무엇이 실패했는지 알 수 있는가?
- skip과 retry는 왜 허용되었는가?
- 처리량이 부족할 때 어떤 순서로 확장할 것인가?

이 질문에 답하지 못하는 배치는 `job / step / reader / processor / writer` 폴더가 있어도 운영 가능한 배치가 아니다.

## References

- Spring Batch Reference - The Domain Language of Batch: https://docs.spring.io/spring-batch/reference/domain.html
- Spring Batch Reference - What's new in Spring Batch 6: https://docs.spring.io/spring-batch/reference/whatsnew.html
- Spring Batch Reference - Scaling and Parallel Processing: https://docs.spring.io/spring-batch/reference/scalability.html
- Spring Batch Reference - Item Reader and Writer Implementations: https://docs.spring.io/spring-batch/reference/readers-and-writers/item-reader-writer-implementations.html
- Spring Batch Reference - Configuring a JobRepository: https://docs.spring.io/spring-batch/reference/job/configuring-repository.html
- Spring Batch Reference - Unit Testing: https://docs.spring.io/spring-batch/reference/testing.html
- Spring Boot System Requirements: https://docs.spring.io/spring-boot/system-requirements.html
