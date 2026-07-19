package stock.batch.service.scheduler;

public final class StockBatchSchedulerNames {

    public static final String EXECUTION = "stockBatchExecutionTaskScheduler";
    public static final String AUTO_MARKET = "stockBatchAutoMarketTaskScheduler";
    public static final String MAINTENANCE = "stockBatchMaintenanceTaskScheduler";
    public static final String POST_CLOSE = "stockBatchPostCloseTaskScheduler";
    public static final String SIMULATION_CLOCK = "stockBatchSimulationClockTaskScheduler";

    private StockBatchSchedulerNames() {
    }
}
