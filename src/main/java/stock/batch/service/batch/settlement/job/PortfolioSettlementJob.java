package stock.batch.service.batch.settlement.job;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import stock.batch.service.batch.common.support.StockBatchJobParameters;
import stock.batch.service.batch.config.BatchRepositoryDataSourceConfig;
import stock.batch.service.batch.settlement.model.AccountSettlementTarget;
import stock.batch.service.batch.settlement.model.PortfolioSnapshotCommand;
import stock.batch.service.batch.settlement.processor.PortfolioSnapshotProcessor;
import stock.batch.service.batch.settlement.reader.AccountSettlementTargetReader;
import stock.batch.service.batch.settlement.writer.PortfolioSettlementItemWriter;
import stock.batch.service.batch.settlement.writer.PortfolioSnapshotWriter;
import stock.batch.service.marketclose.biz.MarketCloseRolloverService;
import stock.batch.service.simulation.SimulationMarketSessionService;

@Configuration(proxyBeanMethods = false)
public class PortfolioSettlementJob {

    public static final String JOB_NAME = "portfolio-settlement";
    public static final String STEP_NAME = "portfolio-settlement-step";
    public static final String READER_NAME = "portfolioSettlementPagingReader";
    public static final String WRITER_NAME = "portfolioSettlementChunkWriter";

    @Bean(name = JOB_NAME)
    public Job portfolioSettlementBatchJob(
            JobRepository jobRepository,
            @Qualifier(STEP_NAME) Step portfolioSettlementStep
    ) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(portfolioSettlementStep)
                .build();
    }

    @Bean(name = STEP_NAME)
    public Step portfolioSettlementStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryDataSourceConfig.BUSINESS_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            @Value("${stock.batch.settlement.chunk-size:200}") int chunkSize,
            @Qualifier(READER_NAME) JdbcPagingItemReader<AccountSettlementTarget> reader,
            PortfolioSnapshotProcessor processor,
            @Qualifier(WRITER_NAME) PortfolioSettlementItemWriter writer
    ) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("stock.batch.settlement.chunk-size must be positive");
        }
        return new StepBuilder(STEP_NAME, jobRepository)
                .<AccountSettlementTarget, PortfolioSnapshotCommand>chunk(chunkSize)
                .reader(reader)
                .processor(processor::process)
                .writer(writer)
                .transactionManager(transactionManager)
                .build();
    }

    @Bean(name = READER_NAME)
    @StepScope
    public JdbcPagingItemReader<AccountSettlementTarget> portfolioSettlementPagingReader(
            AccountSettlementTargetReader readerFactory,
            SimulationMarketSessionService simulationMarketSessionService,
            MarketCloseRolloverService marketCloseRolloverService,
            @Value("${stock.batch.settlement.chunk-size:200}") int pageSize,
            @Value("#{jobParameters['" + StockBatchJobParameters.BUSINESS_DATE + "']}") LocalDate businessDate,
            @Value("#{jobParameters['" + StockBatchJobParameters.ENFORCE_CLOSE + "']}") String enforceClose
    ) throws Exception {
        boolean eligible = !Boolean.parseBoolean(enforceClose)
                || (simulationMarketSessionService.isAfterCloseSession()
                && marketCloseRolloverService.hasCompletedFullCloseRun(businessDate));
        return readerFactory.create(pageSize, eligible);
    }

    @Bean(name = WRITER_NAME)
    @StepScope
    public PortfolioSettlementItemWriter portfolioSettlementChunkWriter(
            PortfolioSnapshotWriter writer,
            @Value("#{jobParameters['" + StockBatchJobParameters.BUSINESS_DATE + "']}") LocalDate snapshotDate,
            @Value("#{jobParameters['" + StockBatchJobParameters.SNAPSHOT_AT + "']}") LocalDateTime snapshotAt
    ) {
        return new PortfolioSettlementItemWriter(writer, snapshotDate, snapshotAt);
    }
}
