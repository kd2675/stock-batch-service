package stock.batch.service.batch.settlement.processor;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import stock.batch.service.batch.settlement.model.AccountSettlementTarget;
import stock.batch.service.batch.settlement.model.PortfolioReturnRateStatus;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioSnapshotProcessorTest {

    private final PortfolioSnapshotProcessor processor = new PortfolioSnapshotProcessor();

    @Test
    void process_zeroContribution_keepsProfitAndMarksReturnUndefined() {
        var result = processor.process(target("1000.00", "0.00"));

        assertThat(result.totalProfit() + ":" + result.returnRate() + ":" + result.returnRateStatus())
                .isEqualTo("1000.00:null:" + PortfolioReturnRateStatus.UNDEFINED_ZERO_CONTRIBUTION);
    }

    @Test
    void process_positiveContribution_calculatesEightDecimalReturn() {
        var result = processor.process(target("800.00", "1000.00"));

        assertThat(result.returnRate()).isEqualByComparingTo(new BigDecimal("-20.00000000"));
    }

    private AccountSettlementTarget target(String cashBalance, String netContribution) {
        return new AccountSettlementTarget(
                1L,
                2L,
                3L,
                "participant",
                new BigDecimal(cashBalance),
                new BigDecimal(netContribution),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0L,
                0L,
                0L
        );
    }
}
