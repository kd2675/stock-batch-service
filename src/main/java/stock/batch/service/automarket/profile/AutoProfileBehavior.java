package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.model.RecurringCashIntervalUnit;

public interface AutoProfileBehavior {

    String BUY = "BUY";
    String SELL = "SELL";

    AutoParticipantProfileType type();

    ProfilePolicy defaultPolicy();

    default int activityLevel(AutoParticipantStrategy strategy) {
        return Math.clamp(strategy.intensity(), 1, 10);
    }

    int orderCount(ProfileSignalContext context);

    String chooseSide(ProfileSignalContext context);

    double buyBias(ProfileSignalContext context);

    int orderTtlSeconds(int baseTtlSeconds, ProfilePolicy policy);

    default BigDecimal recurringDepositAmount(ProfilePolicy policy) {
        return policy.recurringDepositAmount();
    }

    default BigDecimal recurringDepositIntervalValue(ProfilePolicy policy) {
        return policy.recurringDepositIntervalValue();
    }

    default RecurringCashIntervalUnit recurringDepositIntervalUnit(ProfilePolicy policy) {
        return policy.recurringDepositIntervalUnit();
    }

}
