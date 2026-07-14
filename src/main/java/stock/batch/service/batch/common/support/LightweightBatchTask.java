package stock.batch.service.batch.common.support;

public interface LightweightBatchTask {

    String taskName();

    String executionMode();

    default boolean requiresJobLock() {
        return false;
    }

    int run();
}
