package stock.batch.service.batch.common.support;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.step.Step;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class StockBatchStepDefinitionContractTest {

    @Autowired
    private Map<String, Step> steps;

    @Test
    void stepBeans_withoutActiveStepContext_resolveConcreteStepNames() {
        assertThat(steps.values())
                .isNotEmpty()
                .extracting(Step::getName)
                .doesNotContainNull()
                .doesNotHaveDuplicates()
                .allMatch(name -> !name.isBlank());
    }
}
