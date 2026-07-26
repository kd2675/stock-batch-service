package stock.batch.service.automarket.biz;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
class InstitutionShadowPortfolioRunMetrics {

    private final Counter completed;
    private final Counter failed;
    private final Counter skipped;
    private final Counter unexpectedFailure;

    InstitutionShadowPortfolioRunMetrics(MeterRegistry meterRegistry) {
        completed = counter(meterRegistry, "completed");
        failed = counter(meterRegistry, "failed");
        skipped = counter(meterRegistry, "skipped");
        unexpectedFailure = counter(meterRegistry, "rollback");
    }

    void record(InstitutionShadowPortfolioProcessor.ProcessResult result) {
        switch (result) {
            case COMPLETED -> completed.increment();
            case FAILED -> failed.increment();
            case SKIPPED -> skipped.increment();
        }
    }

    void recordUnexpectedFailure() {
        unexpectedFailure.increment();
    }

    private Counter counter(MeterRegistry meterRegistry, String result) {
        return Counter.builder("stock.institution.shadow.decision")
                .tag("result", result)
                .register(meterRegistry);
    }
}
