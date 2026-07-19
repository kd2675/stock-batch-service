package stock.batch.service.batch.common.support;

public interface LightweightBatchTask {

    String taskName();

    String executionMode();

    default boolean requiresJobLock() {
        return false;
    }

    int run();

    default int runForPostCloseCycle(long closeCycleId) {
        return run();
    }
}
