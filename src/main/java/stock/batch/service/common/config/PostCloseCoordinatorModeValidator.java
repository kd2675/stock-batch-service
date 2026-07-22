package stock.batch.service.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class PostCloseCoordinatorModeValidator implements ApplicationRunner {

    private final boolean coordinatorEnabled;
    private final boolean corporateActionsEnabled;
    private final boolean recurringCashEnabled;

    public PostCloseCoordinatorModeValidator(
            @Value("${stock.batch.post-close.coordinator.enabled:true}") boolean coordinatorEnabled,
            @Value("${stock.batch.corporate-actions.enabled:true}") boolean corporateActionsEnabled,
            @Value("${stock.batch.auto-participant-cash-flow.enabled:true}") boolean recurringCashEnabled
    ) {
        this.coordinatorEnabled = coordinatorEnabled;
        this.corporateActionsEnabled = corporateActionsEnabled;
        this.recurringCashEnabled = recurringCashEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!coordinatorEnabled && (corporateActionsEnabled || recurringCashEnabled)) {
            throw new IllegalStateException(
                    "Post-close coordinator must be enabled when corporate actions or recurring cash are enabled"
            );
        }
    }
}
