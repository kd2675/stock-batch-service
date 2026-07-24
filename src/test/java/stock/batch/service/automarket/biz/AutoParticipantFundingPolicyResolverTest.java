package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

import stock.batch.service.automarket.profile.ProfileFundingPolicy;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.RecurringCashIntervalUnit;

class AutoParticipantFundingPolicyResolverTest {

    @Test
    void fromProfileConfigs_savedFundingValues_areResolvedOutsideTradingPolicy() {
        AutoParticipantProfileConfig config = mock(AutoParticipantProfileConfig.class);
        when(config.profileType()).thenReturn(AutoParticipantProfileType.PAYDAY_ACCUMULATOR);
        when(config.recurringDepositAmount()).thenReturn(new BigDecimal("5000000.00"));
        when(config.recurringDepositIntervalValue()).thenReturn(new BigDecimal("3.0000"));
        when(config.recurringDepositIntervalUnit()).thenReturn(RecurringCashIntervalUnit.DAY);

        var policies = AutoParticipantFundingPolicyResolver.fromProfileConfigs(List.of(config));
        var policy = AutoParticipantFundingPolicyResolver.resolve(
                policies,
                AutoParticipantProfileType.PAYDAY_ACCUMULATOR
        );

        assertThat(List.of(policy))
                .extracting(
                        ProfileFundingPolicy::recurringDepositAmount,
                        ProfileFundingPolicy::recurringDepositIntervalValue,
                        ProfileFundingPolicy::recurringDepositIntervalUnit
                )
                .containsExactly(Tuple.tuple(
                        new BigDecimal("5000000.00"),
                        new BigDecimal("3.0000"),
                        RecurringCashIntervalUnit.DAY
                ));
    }

    @Test
    void resolve_missingProfileConfig_disablesRecurringFunding() {
        var policy = AutoParticipantFundingPolicyResolver.resolve(
                java.util.Map.of(),
                AutoParticipantProfileType.DIVIDEND_REINVESTOR
        );

        assertThat(List.of(policy))
                .extracting(
                        ProfileFundingPolicy::recurringDepositAmount,
                        ProfileFundingPolicy::recurringDepositIntervalValue
                )
                .containsExactly(Tuple.tuple(BigDecimal.ZERO, BigDecimal.ZERO));
    }
}
