package stock.batch.service.batch.signal.biz;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import stock.batch.service.common.vo.StockBatchJobRunResponse;
import stock.batch.service.testsupport.BatchTestDatabaseFactory;

import static org.assertj.core.api.Assertions.assertThat;

class BatchJobSignalWriterTest {

    @Test
    void defer_requeuesProcessingSignalAsPending() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                BatchTestDatabaseFactory.createDataSource("batch_job_signal_writer_test")
        );
        createSignalTable(jdbcTemplate);
        LocalDateTime now = LocalDateTime.of(2026, 7, 8, 17, 30);
        jdbcTemplate.update(
                """
                insert into stock_batch_job_signal(
                    id, signal_type, job_name, execution_mode, status,
                    requested_at, picked_at, completed_at, processed_count,
                    message, error_message, created_at, updated_at
                )
                values (10, 'MARKET_CLOSE_ROLLOVER_RUN', 'market-close-rollover', 'price-limit-base',
                        'PROCESSING', ?, ?, ?, 3, 'old message', 'old error', ?, ?)
                """,
                now.minusMinutes(1),
                now,
                now,
                now.minusMinutes(1),
                now
        );
        BatchJobSignalWriter writer = new BatchJobSignalWriter(JdbcClient.create(jdbcTemplate));
        StockBatchJobRunResponse response = new StockBatchJobRunResponse(
                "market-close-rollover",
                "SKIPPED",
                "price-limit-base",
                0,
                "Job is already running",
                now,
                now
        );

        writer.defer(10L, response);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                select status, picked_at, completed_at, processed_count, message, error_message
                  from stock_batch_job_signal
                 where id = 10
                """
        );
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("picked_at")).isNull();
        assertThat(row.get("completed_at")).isNull();
        assertThat(row.get("processed_count")).isEqualTo(0);
        assertThat(row.get("message")).isEqualTo("Job is already running");
        assertThat(row.get("error_message")).isNull();
    }

    private void createSignalTable(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute(
                """
                create table stock_batch_job_signal (
                    id bigint primary key,
                    signal_type varchar(60) not null,
                    job_name varchar(100) not null,
                    execution_mode varchar(120) not null,
                    symbol varchar(20),
                    payload_json clob,
                    status varchar(20) not null,
                    requested_by varchar(64),
                    requested_at timestamp not null,
                    picked_at timestamp,
                    completed_at timestamp,
                    processed_count int,
                    message varchar(500),
                    error_message varchar(1000),
                    created_at timestamp not null,
                    updated_at timestamp not null
                )
                """
        );
    }
}
