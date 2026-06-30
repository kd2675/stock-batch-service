package stock.batch.service.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import stock.batch.service.automarket.biz.AutoParticipantCashFlowRuntimeControl;
import stock.batch.service.batch.automarket.job.AutoMarketJob;
import stock.batch.service.batch.common.policy.BatchJobRuntimeControl;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.batch.corporateaction.job.CorporateActionJob;
import stock.batch.service.batch.execution.job.OrderBookExecutionJob;
import stock.batch.service.batch.execution.job.VirtualPriceExecutionJob;
import stock.batch.service.batch.marketclose.job.MarketCloseRolloverJob;
import stock.batch.service.batch.marketdata.job.MarketDataRefreshJob;
import stock.batch.service.batch.settlement.job.PortfolioSettlementJob;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AutoParticipantCashFlowSchedulerTest {

    private final StockBatchJobLauncher stockBatchJobLauncher = mock(StockBatchJobLauncher.class);
    private final JdbcTemplate jdbcTemplate = createJdbcTemplate();
    private final BatchJobRuntimeControl batchJobRuntimeControl = new BatchJobRuntimeControl(jdbcTemplate);
    private final AutoParticipantCashFlowRuntimeControl runtimeControl =
            new AutoParticipantCashFlowRuntimeControl(batchJobRuntimeControl, true);
    private final AutoParticipantCashFlowScheduler scheduler =
            createAutoParticipantCashFlowScheduler();

    @Test
    void fundAutoParticipants_runtimeEnabled_runsJob() {
        scheduler.fundAutoParticipants();

        verify(stockBatchJobLauncher).fundAutoParticipants();
    }

    @Test
    void fundAutoParticipants_runtimeDisabled_skipsJob() {
        runtimeControl.update(false, "stock-admin");

        scheduler.fundAutoParticipants();

        verify(stockBatchJobLauncher, never()).fundAutoParticipants();
    }

    @Test
    void status_secondRuntimeControlReadsSharedDatabaseState() {
        runtimeControl.update(false, "stock-admin");

        AutoParticipantCashFlowRuntimeControl otherInstanceControl =
                new AutoParticipantCashFlowRuntimeControl(batchJobRuntimeControl, true);

        assertThat(otherInstanceControl.status().runtimeEnabled()).isFalse();
        assertThat(otherInstanceControl.status().effectiveEnabled()).isFalse();
        assertThat(otherInstanceControl.status().updatedBy()).isEqualTo("stock-admin");
    }

    @Test
    void autoMarketScheduler_runtimeDisabled_skipsJobThroughSharedControlTable() {
        AutoMarketScheduler autoMarketScheduler = new AutoMarketScheduler(stockBatchJobLauncher, batchJobRuntimeControl);
        batchJobRuntimeControl.update(AutoMarketJob.JOB_NAME, true, false, "stock-admin");

        autoMarketScheduler.runAutoMarket();

        verify(stockBatchJobLauncher, never()).runAutoMarket();
    }

    @Test
    void marketDataScheduler_runtimeDisabled_skipsJobThroughSharedControlTable() {
        MarketDataRefreshScheduler marketDataRefreshScheduler =
                new MarketDataRefreshScheduler(stockBatchJobLauncher, batchJobRuntimeControl);
        batchJobRuntimeControl.update(MarketDataRefreshJob.JOB_NAME, true, false, "stock-admin");

        marketDataRefreshScheduler.refreshMarketData();

        verify(stockBatchJobLauncher, never()).refreshMarketData();
    }

    @Test
    void virtualPriceExecutionScheduler_runtimeDisabled_skipsJobThroughSharedControlTable() {
        VirtualPriceExecutionScheduler virtualPriceExecutionScheduler =
                new VirtualPriceExecutionScheduler(stockBatchJobLauncher, batchJobRuntimeControl);
        batchJobRuntimeControl.update(VirtualPriceExecutionJob.JOB_NAME, true, false, "stock-admin");

        virtualPriceExecutionScheduler.executeVirtualPriceOrders();

        verify(stockBatchJobLauncher, never()).executeVirtualPriceOrders();
    }

    @Test
    void orderBookExecutionScheduler_runtimeDisabled_skipsJobThroughSharedControlTable() {
        OrderBookExecutionScheduler orderBookExecutionScheduler =
                new OrderBookExecutionScheduler(stockBatchJobLauncher, batchJobRuntimeControl);
        batchJobRuntimeControl.update(OrderBookExecutionJob.JOB_NAME, true, false, "stock-admin");

        orderBookExecutionScheduler.executeOrderBookOrders();

        verify(stockBatchJobLauncher, never()).executeOrderBookOrders();
    }

    @Test
    void corporateActionScheduler_runtimeDisabled_skipsJobThroughSharedControlTable() {
        CorporateActionScheduler corporateActionScheduler =
                new CorporateActionScheduler(stockBatchJobLauncher, batchJobRuntimeControl);
        batchJobRuntimeControl.update(CorporateActionJob.JOB_NAME, true, false, "stock-admin");

        corporateActionScheduler.applyCorporateActions();

        verify(stockBatchJobLauncher, never()).applyCorporateActions();
    }

    @Test
    void portfolioSettlementScheduler_marketCloseDisabled_stillRunsSettlementOnly() {
        PortfolioSettlementScheduler portfolioSettlementScheduler =
                new PortfolioSettlementScheduler(stockBatchJobLauncher, batchJobRuntimeControl);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "settlementSchedulerConfigured", true);
        batchJobRuntimeControl.update(MarketCloseRolloverJob.JOB_NAME, true, false, "stock-admin");

        portfolioSettlementScheduler.settlePortfolios();

        verify(stockBatchJobLauncher, never()).rolloverClosingPrices();
        verify(stockBatchJobLauncher).settlePortfolios();
    }

    @Test
    void portfolioSettlementScheduler_settlementDisabled_stillRunsMarketCloseOnly() {
        PortfolioSettlementScheduler portfolioSettlementScheduler =
                new PortfolioSettlementScheduler(stockBatchJobLauncher, batchJobRuntimeControl);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "settlementSchedulerConfigured", true);
        batchJobRuntimeControl.update(PortfolioSettlementJob.JOB_NAME, true, false, "stock-admin");

        portfolioSettlementScheduler.settlePortfolios();

        verify(stockBatchJobLauncher).rolloverClosingPrices();
        verify(stockBatchJobLauncher, never()).settlePortfolios();
    }

    @Test
    void portfolioSettlementScheduler_marketCloseScheduleRunsRolloverOnly() {
        PortfolioSettlementScheduler portfolioSettlementScheduler =
                new PortfolioSettlementScheduler(stockBatchJobLauncher, batchJobRuntimeControl);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "settlementSchedulerConfigured", true);

        portfolioSettlementScheduler.rolloverClosingPrices();

        verify(stockBatchJobLauncher).rolloverClosingPrices();
        verify(stockBatchJobLauncher, never()).settlePortfolios();
    }

    @Test
    void portfolioSettlementScheduler_marketCloseConfiguredOff_stillRunsSettlementOnly() {
        PortfolioSettlementScheduler portfolioSettlementScheduler =
                new PortfolioSettlementScheduler(stockBatchJobLauncher, batchJobRuntimeControl);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "marketCloseSchedulerConfigured", false);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "settlementSchedulerConfigured", true);

        portfolioSettlementScheduler.settlePortfolios();

        verify(stockBatchJobLauncher, never()).rolloverClosingPrices();
        verify(stockBatchJobLauncher).settlePortfolios();
    }

    @Test
    void portfolioSettlementScheduler_settlementConfiguredOff_stillRunsMarketCloseOnly() {
        PortfolioSettlementScheduler portfolioSettlementScheduler =
                new PortfolioSettlementScheduler(stockBatchJobLauncher, batchJobRuntimeControl);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "settlementSchedulerConfigured", false);

        portfolioSettlementScheduler.settlePortfolios();

        verify(stockBatchJobLauncher).rolloverClosingPrices();
        verify(stockBatchJobLauncher, never()).settlePortfolios();
    }

    @Test
    void portfolioSettlementScheduler_bothConfiguredOff_skipsBothJobs() {
        PortfolioSettlementScheduler portfolioSettlementScheduler =
                new PortfolioSettlementScheduler(stockBatchJobLauncher, batchJobRuntimeControl);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "marketCloseSchedulerConfigured", false);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "settlementSchedulerConfigured", false);

        portfolioSettlementScheduler.settlePortfolios();

        verify(stockBatchJobLauncher, never()).rolloverClosingPrices();
        verify(stockBatchJobLauncher, never()).settlePortfolios();
    }

    private AutoParticipantCashFlowScheduler createAutoParticipantCashFlowScheduler() {
        return new AutoParticipantCashFlowScheduler(stockBatchJobLauncher, batchJobRuntimeControl);
    }

    private JdbcTemplate createJdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:auto_participant_cash_flow_scheduler_test_%s;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false".formatted(UUID.randomUUID()));
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.execute("""
                create table if not exists stock_batch_job_control (
                  job_name varchar(100) not null primary key,
                  runtime_enabled boolean not null default true,
                  updated_by varchar(64),
                  created_at timestamp not null,
                  updated_at timestamp not null,
                  constraint chk_stock_batch_job_control_name check (job_name <> '')
                )
                """);
        return template;
    }
}
