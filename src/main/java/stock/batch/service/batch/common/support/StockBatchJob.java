package stock.batch.service.batch.common.support;

public interface StockBatchJob {

    String jobName();

    String executionMode();

    default boolean requiresJobLock() {
        return true;
    }

    int run();
}
