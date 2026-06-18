package stock.batch.service.marketdata.biz;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.UUID;

final class TestJdbcTemplateFactory {

    private TestJdbcTemplateFactory() {
    }

    static JdbcTemplate create() {
        var databaseName = "market_data_testdb_" + UUID.randomUUID().toString().replace("-", "");
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        new ResourceDatabasePopulator(new ClassPathResource("db/ddl/stock_h2.sql")).execute(dataSource);
        return new JdbcTemplate(dataSource);
    }
}
