package stock.batch.service.common.act;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import stock.batch.service.automarket.biz.AutoParticipantCashFlowRuntimeControl;
import stock.batch.service.batch.common.policy.BatchJobRuntimeCatalog;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.common.vo.AutoParticipantCashFlowControlRequest;
import stock.batch.service.common.vo.AutoParticipantCashFlowStatusResponse;
import stock.batch.service.common.vo.BatchJobRuntimeControlRequest;
import stock.batch.service.common.vo.BatchJobRuntimeStatusResponse;
import stock.batch.service.common.vo.StockBatchJobRunResponse;
import web.common.core.response.base.dto.ResponseDataDTO;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/stock-batch/v1/jobs")
public class StockBatchJobController {

    private final StockBatchJobLauncher stockBatchJobLauncher;
    private final AutoParticipantCashFlowRuntimeControl autoParticipantCashFlowRuntimeControl;
    private final BatchJobRuntimeCatalog batchJobRuntimeCatalog;

    @PostMapping("/market-data/refresh")
    public ResponseDataDTO<StockBatchJobRunResponse> refreshMarketData() {
        return ResponseDataDTO.of(stockBatchJobLauncher.refreshMarketData());
    }

    @PostMapping("/order-book-execution/run")
    public ResponseDataDTO<StockBatchJobRunResponse> executeOrderBookOrders() {
        return ResponseDataDTO.of(stockBatchJobLauncher.executeOrderBookOrders());
    }

    @PostMapping("/auto-participant-cash-flow/run")
    public ResponseDataDTO<StockBatchJobRunResponse> fundAutoParticipants() {
        return ResponseDataDTO.of(stockBatchJobLauncher.fundAutoParticipantsManually());
    }

    @GetMapping("/auto-participant-cash-flow/status")
    public ResponseDataDTO<AutoParticipantCashFlowStatusResponse> getAutoParticipantCashFlowStatus() {
        return ResponseDataDTO.of(autoParticipantCashFlowRuntimeControl.status());
    }

    @PatchMapping("/auto-participant-cash-flow/status")
    public ResponseDataDTO<AutoParticipantCashFlowStatusResponse> updateAutoParticipantCashFlowStatus(
            @RequestBody AutoParticipantCashFlowControlRequest request
    ) {
        if (request == null || request.runtimeEnabled() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runtimeEnabled is required");
        }
        boolean runtimeEnabled = request.runtimeEnabled();
        return ResponseDataDTO.of(autoParticipantCashFlowRuntimeControl.update(runtimeEnabled, request.updatedBy()));
    }

    @GetMapping("/runtime-controls")
    public ResponseDataDTO<List<BatchJobRuntimeStatusResponse>> getRuntimeControls() {
        return ResponseDataDTO.of(batchJobRuntimeCatalog.statuses());
    }

    @PatchMapping("/runtime-controls/{jobName}")
    public ResponseDataDTO<BatchJobRuntimeStatusResponse> updateRuntimeControl(
            @PathVariable String jobName,
            @RequestBody BatchJobRuntimeControlRequest request
    ) {
        if (request == null || request.runtimeEnabled() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runtimeEnabled is required");
        }
        String decodedJobName = URLDecoder.decode(jobName, StandardCharsets.UTF_8);
        if (!StringUtils.hasText(decodedJobName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "jobName is required");
        }
        try {
            return ResponseDataDTO.of(batchJobRuntimeCatalog.update(decodedJobName, request.runtimeEnabled(), request.updatedBy()));
        } catch (IllegalArgumentException ex) {
            if ("jobName is required".equals(ex.getMessage())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/auto-market/run")
    public ResponseDataDTO<StockBatchJobRunResponse> runAutoMarket() {
        return ResponseDataDTO.of(stockBatchJobLauncher.runAutoMarket());
    }

    @PostMapping("/auto-market-profile-queue/reconcile")
    public ResponseDataDTO<StockBatchJobRunResponse> reconcileAutoMarketProfileQueue() {
        return ResponseDataDTO.of(stockBatchJobLauncher.reconcileAutoMarketProfileQueue());
    }

    @PostMapping("/auto-market-order-expiry/run")
    public ResponseDataDTO<StockBatchJobRunResponse> expireAutoMarketOrders() {
        return ResponseDataDTO.of(stockBatchJobLauncher.expireAutoMarketOrders());
    }

    @PostMapping("/listing-auto-market/run")
    public ResponseDataDTO<StockBatchJobRunResponse> runListingAutoMarket() {
        return ResponseDataDTO.of(stockBatchJobLauncher.runListingAutoMarket());
    }

    @PostMapping("/portfolio-settlement/run")
    public ResponseDataDTO<StockBatchJobRunResponse> settlePortfolios() {
        return ResponseDataDTO.of(stockBatchJobLauncher.settlePortfolios());
    }

    @PostMapping("/market-close/rollover")
    public ResponseDataDTO<StockBatchJobRunResponse> rolloverClosingPrices() {
        return ResponseDataDTO.of(stockBatchJobLauncher.rolloverClosingPrices());
    }

    @PostMapping("/market-close/rollover/{symbol}")
    public ResponseDataDTO<StockBatchJobRunResponse> rolloverClosingPrices(@PathVariable String symbol) {
        String decodedSymbol = URLDecoder.decode(symbol, StandardCharsets.UTF_8);
        if (!StringUtils.hasText(decodedSymbol)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "symbol is required");
        }
        return ResponseDataDTO.of(stockBatchJobLauncher.rolloverClosingPrices(decodedSymbol));
    }

    @PostMapping("/order-book-orders/cancel-open/{symbol}")
    public ResponseDataDTO<StockBatchJobRunResponse> cancelOpenOrderBookOrders(@PathVariable String symbol) {
        String decodedSymbol = URLDecoder.decode(symbol, StandardCharsets.UTF_8);
        if (!StringUtils.hasText(decodedSymbol)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "symbol is required");
        }
        return ResponseDataDTO.of(stockBatchJobLauncher.cancelOpenOrderBookOrders(decodedSymbol));
    }

    @PostMapping("/corporate-actions/run")
    public ResponseDataDTO<StockBatchJobRunResponse> applyCorporateActions() {
        return ResponseDataDTO.of(stockBatchJobLauncher.applyCorporateActions());
    }
}
