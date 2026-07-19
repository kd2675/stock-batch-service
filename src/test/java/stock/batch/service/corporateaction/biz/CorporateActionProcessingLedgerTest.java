package stock.batch.service.corporateaction.biz;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class CorporateActionProcessingLedgerTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 15);
    private static final LocalDateTime PROCESSED_AT = BUSINESS_DATE.atTime(0, 10);

    @Autowired
    private CorporateActionProcessingLedger processingLedger;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from stock_corporate_action_processing");
    }

    @Test
    void sumCompletedAccountProcessedCount_restartedChunks_includesPriorAndCurrentUnits() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            processingLedger.completeAccount(
                    91L, 101L, "public-offering-auto-subscription", BUSINESS_DATE,
                    1, new BigDecimal("1000.00"), 10L, "account:101", PROCESSED_AT
            );
            processingLedger.completeAccount(
                    91L, 102L, "public-offering-auto-subscription", BUSINESS_DATE,
                    0, BigDecimal.ZERO, 0L, null, PROCESSED_AT
            );
            processingLedger.completeAccount(
                    91L, 103L, "public-offering-auto-subscription", BUSINESS_DATE,
                    1, new BigDecimal("2000.00"), 20L, "account:103", PROCESSED_AT
            );
        });

        Integer processedCount = transaction.execute(status -> processingLedger.sumCompletedAccountProcessedCount(
                91L,
                "public-offering-auto-subscription",
                BUSINESS_DATE
        ));

        assertThat(processedCount).isEqualTo(2);
    }
}
