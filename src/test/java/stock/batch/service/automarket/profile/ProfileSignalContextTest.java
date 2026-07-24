package stock.batch.service.automarket.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class ProfileSignalContextTest {

    @Test
    void hasHolding_fullyReservedPosition_keepsOwnershipSeparateFromSellAvailability() {
        ParticipantPortfolioSnapshot portfolio = new ParticipantPortfolioSnapshot(
                100L,
                100L,
                BigDecimal.ZERO,
                new BigDecimal("10000.00"),
                new BigDecimal("10000.00"),
                1,
                0L,
                1
        );
        ProfileSignalContext context = new ProfileSignalContext(
                null,
                null,
                null,
                5,
                0.0,
                0.0,
                0.0,
                0L,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                false,
                0,
                0.0,
                portfolio,
                MarketSignalSnapshot.EMPTY,
                FundingBudgetSnapshot.EMPTY,
                BehavioralMemory.EMPTY
        );

        assertThat(List.of(context.hasHolding(), context.hasAvailableHolding()))
                .containsExactly(true, false);
    }

    @Test
    void marketMakerTargetAllocation_usesEligibleUniverseInsteadOfCurrentPositionCount() {
        ParticipantPortfolioSnapshot portfolio = new ParticipantPortfolioSnapshot(
                100L,
                0L,
                BigDecimal.ZERO,
                new BigDecimal("10000.00"),
                new BigDecimal("100000.00"),
                1,
                0L,
                3
        );
        ProfileSignalContext context = new ProfileSignalContext(
                null,
                null,
                null,
                5,
                0.0,
                0.0,
                0.0,
                100L,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                false,
                0,
                0.0,
                portfolio,
                MarketSignalSnapshot.EMPTY,
                FundingBudgetSnapshot.EMPTY,
                BehavioralMemory.EMPTY
        );

        assertThat(context.marketMakerTargetAllocationRatio()).isEqualTo(0.50 / 3.0);
    }
}
