package stock.batch.service.marketdata.biz;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import stock.batch.service.testsupport.BatchTestDatabaseFactory;

final class TestJdbcTemplateFactory {

    private TestJdbcTemplateFactory() {
    }

    static JdbcTemplate create() {
        var dataSource = BatchTestDatabaseFactory.createDataSource("market_data_testdb");
        new ResourceDatabasePopulator(new ClassPathResource("db/ddl/stock_h2.sql")).execute(dataSource);
        return new JdbcTemplate(dataSource);
    }
}
