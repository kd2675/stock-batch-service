package stock.batch.service.testsupport;

import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public final class BatchTestDatabaseFactory {

    private BatchTestDatabaseFactory() {
    }

    public static DataSource createDataSource(String databaseNamePrefix) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:%s_%s;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
                .formatted(databaseNamePrefix, UUID.randomUUID()));
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    public static JdbcTemplate createJobControlJdbcTemplate(String databaseNamePrefix) {
        JdbcTemplate template = new JdbcTemplate(createDataSource(databaseNamePrefix));
        template.execute("""
                create table if not exists stock_batch_job_control (
                  job_name varchar(100) not null primary key,
                  runtime_enabled boolean not null default true,
                  scheduler_configured boolean not null default true,
                  updated_by varchar(64),
                  created_at timestamp not null,
                  updated_at timestamp not null,
                  constraint chk_stock_batch_job_control_name check (job_name <> '')
                )
                """);
        return template;
    }

    public static JdbcTemplate createJobLockJdbcTemplate(String databaseNamePrefix) {
        JdbcTemplate template = new JdbcTemplate(createDataSource(databaseNamePrefix));
        template.execute("""
                create table if not exists stock_batch_job_lock (
                  job_name varchar(100) not null primary key,
                  lock_owner varchar(128) not null,
                  locked_until timestamp not null,
                  created_at timestamp not null,
                  updated_at timestamp not null,
                  constraint chk_stock_batch_job_lock_name check (job_name <> ''),
                  constraint chk_stock_batch_job_lock_owner check (lock_owner <> '')
                )
                """);
        return template;
    }
}
