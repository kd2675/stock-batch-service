package stock.batch.service.common.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostCloseCoordinatorModeValidatorTest {

    @Test
    void run_coordinatorDisabledWithCorporateActionsEnabled_failsClosed() {
        PostCloseCoordinatorModeValidator validator = new PostCloseCoordinatorModeValidator(false, true, false);

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Post-close coordinator must be enabled");
    }

    @Test
    void run_coordinatorDisabledWithRecurringCashEnabled_failsClosed() {
        PostCloseCoordinatorModeValidator validator = new PostCloseCoordinatorModeValidator(false, false, true);

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Post-close coordinator must be enabled");
    }

    @Test
    void run_allPostCloseFeaturesDisabled_allowsCoordinatorDisabled() {
        PostCloseCoordinatorModeValidator validator = new PostCloseCoordinatorModeValidator(false, false, false);

        assertThatCode(() -> validator.run(null)).doesNotThrowAnyException();
    }
}
