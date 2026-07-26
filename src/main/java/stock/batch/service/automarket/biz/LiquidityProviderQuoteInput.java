package stock.batch.service.automarket.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoOrder;

record LiquidityProviderQuoteInput(
        LiquidityProviderMandate mandate,
        AutoMarketConfig marketConfig,
        LocalDate simulationTradeDate,
        LocalDateTime now,
        LiquidityProviderAccountSnapshot account,
        LiquidityProviderExecutionSnapshot executions,
        LiquidityProviderDailyState dailyState,
        LiquidityProviderExternalBook externalBook,
        List<AutoOrder> openOrders,
        boolean openOrderOverflow,
        boolean marketTradingEnabled,
        int enabledLegacyLiquidityConfigCount
) {

    LiquidityProviderQuoteInput {
        openOrders = openOrders == null ? List.of() : List.copyOf(openOrders);
    }
}
