package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import stock.batch.service.automarket.profile.ProfileFundingPolicy;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.RecurringCashIntervalUnit;

final class AutoParticipantFundingPolicyResolver {

    private static final ProfileFundingPolicy NO_RECURRING_FUNDING = new ProfileFundingPolicy(
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            RecurringCashIntervalUnit.DAY
    );

    private AutoParticipantFundingPolicyResolver() {
    }

    static Map<AutoParticipantProfileType, ProfileFundingPolicy> fromProfileConfigs(
            List<AutoParticipantProfileConfig> configs
    ) {
        Map<AutoParticipantProfileType, ProfileFundingPolicy> policies =
                new EnumMap<>(AutoParticipantProfileType.class);
        for (AutoParticipantProfileConfig config : configs) {
            policies.put(config.profileType(), new ProfileFundingPolicy(
                    config.recurringDepositAmount(),
                    config.recurringDepositIntervalValue(),
                    config.recurringDepositIntervalUnit()
            ));
        }
        return Map.copyOf(policies);
    }

    static ProfileFundingPolicy resolve(
            Map<AutoParticipantProfileType, ProfileFundingPolicy> policies,
            AutoParticipantProfileType profileType
    ) {
        return policies.getOrDefault(profileType, NO_RECURRING_FUNDING);
    }
}
