package stock.batch.service.automarket.biz;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoMarketDailyRegime;
import stock.batch.service.batch.automarket.model.AutoMarketDistributionBias;
import stock.batch.service.batch.automarket.model.AutoMarketPressure;
import stock.batch.service.batch.automarket.model.AutoMarketRegimePhase;
import stock.batch.service.batch.automarket.model.AutoMarketRegimeModifier;

import static stock.batch.service.automarket.support.AutoMarketPressureSampler.sample;

@Component
@RequiredArgsConstructor
class AutoMarketDailyRegimeService {

    private final JdbcTemplate jdbcTemplate;
    List<AutoMarketConfig> applyDailyRegimes(
            List<AutoMarketConfig> configs,
            LocalDate simulationTradeDate,
            LocalDateTime now
    ) {
        if (configs.isEmpty()) {
            return List.of();
        }
        AutoMarketRegimePhase regimePhase = resolveRegimePhase(now);
        ensureDailyRegimes(configs, simulationTradeDate, now, regimePhase);
        LocalDateTime modifierWindowStartAt = modifierWindowStartAt(now);
        ensureRegimeModifiers(configs, simulationTradeDate, now, regimePhase, modifierWindowStartAt);
        Map<String, AutoMarketDailyRegime> regimesBySymbol = findDailyRegimes(
                configs.stream()
                        .map(AutoMarketConfig::symbol)
                        .distinct()
                        .toList(),
                simulationTradeDate,
                regimePhase
        );
        Map<String, AutoMarketRegimeModifier> modifiersBySymbol = findRegimeModifiers(
                configs.stream()
                        .map(AutoMarketConfig::symbol)
                        .distinct()
                        .toList(),
                simulationTradeDate,
                regimePhase,
                modifierWindowStartAt
        );
        return configs.stream()
                .map(config -> config.withDailyRegime(regimesBySymbol.get(config.symbol()))
                        .withRegimeModifier(modifiersBySymbol.get(config.symbol())))
                .toList();
    }

    int ensureDailyRegimes(
            List<AutoMarketConfig> configs,
            LocalDate simulationTradeDate,
            LocalDateTime now
    ) {
        return ensureDailyRegimes(configs, simulationTradeDate, now, resolveRegimePhase(now));
    }

    int ensureFirstSlotDailyRegimes(
            List<AutoMarketConfig> configs,
            LocalDate simulationTradeDate,
            LocalDateTime now
    ) {
        return ensureDailyRegimes(configs, simulationTradeDate, now, AutoMarketRegimePhase.SLOT_0600);
    }

    private int ensureDailyRegimes(
            List<AutoMarketConfig> configs,
            LocalDate simulationTradeDate,
            LocalDateTime now,
            AutoMarketRegimePhase regimePhase
    ) {
        int createdCount = 0;
        for (AutoMarketConfig config : configs) {
            if (ensureDailyRegime(config, simulationTradeDate, now, regimePhase)) {
                createdCount++;
            }
        }
        return createdCount;
    }

    private boolean ensureDailyRegime(
            AutoMarketConfig config,
            LocalDate simulationTradeDate,
            LocalDateTime now,
            AutoMarketRegimePhase regimePhase
    ) {
        AutoMarketDailyRegime regime = createRandomRegime(config, simulationTradeDate, regimePhase);
        try {
            jdbcTemplate.update(
                    """
                    insert into stock_order_book_daily_regime(
                        symbol, simulation_trade_date, regime_phase, price_pressure, asset_preference_pressure,
                        volatility_pressure, liquidity_pressure, execution_aggression_pressure, seed,
                        created_at, updated_at
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    regime.symbol(),
                    Date.valueOf(regime.simulationTradeDate()),
                    regime.regimePhase().name(),
                    regime.pressure().price(),
                    regime.pressure().assetPreference(),
                    regime.pressure().volatility(),
                    regime.pressure().liquidity(),
                    regime.pressure().executionAggression(),
                    regime.seed(),
                    now,
                    now
            );
            return true;
        } catch (DuplicateKeyException ignored) {
            // Another batch process opened this symbol/day first.
            return false;
        }
    }

    private Map<String, AutoMarketDailyRegime> findDailyRegimes(
            List<String> symbols,
            LocalDate simulationTradeDate,
            AutoMarketRegimePhase regimePhase
    ) {
        if (symbols.isEmpty()) {
            return Map.of();
        }
        return JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate))
                .sql(
                        """
                        select symbol,
                               simulation_trade_date,
                               regime_phase,
                               price_pressure,
                               asset_preference_pressure,
                               volatility_pressure,
                               liquidity_pressure,
                               execution_aggression_pressure,
                               seed
                         from stock_order_book_daily_regime
                         where symbol in (:symbols)
                           and simulation_trade_date = :simulationTradeDate
                           and regime_phase = :regimePhase
                         order by symbol asc
                        """
                )
                .param("symbols", symbols)
                .param("simulationTradeDate", simulationTradeDate)
                .param("regimePhase", regimePhase.name())
                .query((rs, rowNum) -> new AutoMarketDailyRegime(
                        rs.getString("symbol"),
                        rs.getDate("simulation_trade_date").toLocalDate(),
                        AutoMarketRegimePhase.parseOrDefault(rs.getString("regime_phase")),
                        new AutoMarketPressure(
                                rs.getInt("price_pressure"),
                                rs.getInt("asset_preference_pressure"),
                                rs.getInt("volatility_pressure"),
                                rs.getInt("liquidity_pressure"),
                                rs.getInt("execution_aggression_pressure")
                        ),
                        rs.getLong("seed")
                ))
                .list()
                .stream()
                .collect(LinkedHashMap::new, (map, regime) -> map.put(regime.symbol(), regime), LinkedHashMap::putAll);
    }

    private AutoMarketDailyRegime createRandomRegime(
            AutoMarketConfig config,
            LocalDate simulationTradeDate,
            AutoMarketRegimePhase regimePhase
    ) {
        long seed = ThreadLocalRandom.current().nextLong();
        Random random = new Random(seed);
        return new AutoMarketDailyRegime(
                config.symbol(),
                simulationTradeDate,
                regimePhase,
                samplePressures(random, config.primaryDistributionBias()),
                seed
        );
    }

    private void ensureRegimeModifiers(
            List<AutoMarketConfig> configs,
            LocalDate simulationTradeDate,
            LocalDateTime now,
            AutoMarketRegimePhase regimePhase,
            LocalDateTime modifierWindowStartAt
    ) {
        for (AutoMarketConfig config : configs) {
            ensureRegimeModifier(config, simulationTradeDate, now, regimePhase, modifierWindowStartAt);
        }
    }

    private boolean ensureRegimeModifier(
            AutoMarketConfig config,
            LocalDate simulationTradeDate,
            LocalDateTime now,
            AutoMarketRegimePhase regimePhase,
            LocalDateTime modifierWindowStartAt
    ) {
        AutoMarketRegimeModifier modifier = createRandomModifier(config, simulationTradeDate, regimePhase, modifierWindowStartAt);
        try {
            jdbcTemplate.update(
                    """
                    insert into stock_order_book_regime_modifier(
                        symbol, simulation_trade_date, regime_phase, modifier_window_start_at,
                        price_pressure, asset_preference_pressure, volatility_pressure,
                        liquidity_pressure, execution_aggression_pressure,
                        seed, created_at, updated_at
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    modifier.symbol(),
                    Date.valueOf(modifier.simulationTradeDate()),
                    modifier.regimePhase().name(),
                    modifier.modifierWindowStartAt(),
                    modifier.pressure().price(),
                    modifier.pressure().assetPreference(),
                    modifier.pressure().volatility(),
                    modifier.pressure().liquidity(),
                    modifier.pressure().executionAggression(),
                    modifier.seed(),
                    now,
                    now
            );
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    private Map<String, AutoMarketRegimeModifier> findRegimeModifiers(
            List<String> symbols,
            LocalDate simulationTradeDate,
            AutoMarketRegimePhase regimePhase,
            LocalDateTime modifierWindowStartAt
    ) {
        if (symbols.isEmpty()) {
            return Map.of();
        }
        return JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate))
                .sql(
                        """
                        select symbol,
                               simulation_trade_date,
                               regime_phase,
                               modifier_window_start_at,
                               price_pressure,
                               asset_preference_pressure,
                               volatility_pressure,
                               liquidity_pressure,
                               execution_aggression_pressure,
                               seed
                         from stock_order_book_regime_modifier
                         where symbol in (:symbols)
                           and simulation_trade_date = :simulationTradeDate
                           and regime_phase = :regimePhase
                           and modifier_window_start_at = :modifierWindowStartAt
                         order by symbol asc
                        """
                )
                .param("symbols", symbols)
                .param("simulationTradeDate", simulationTradeDate)
                .param("regimePhase", regimePhase.name())
                .param("modifierWindowStartAt", modifierWindowStartAt)
                .query((rs, rowNum) -> new AutoMarketRegimeModifier(
                        rs.getString("symbol"),
                        rs.getDate("simulation_trade_date").toLocalDate(),
                        AutoMarketRegimePhase.parseOrDefault(rs.getString("regime_phase")),
                        rs.getTimestamp("modifier_window_start_at").toLocalDateTime(),
                        new AutoMarketPressure(
                                rs.getInt("price_pressure"),
                                rs.getInt("asset_preference_pressure"),
                                rs.getInt("volatility_pressure"),
                                rs.getInt("liquidity_pressure"),
                                rs.getInt("execution_aggression_pressure")
                        ),
                        rs.getLong("seed")
                ))
                .list()
                .stream()
                .collect(LinkedHashMap::new, (map, modifier) -> map.put(modifier.symbol(), modifier), LinkedHashMap::putAll);
    }

    private AutoMarketRegimeModifier createRandomModifier(
            AutoMarketConfig config,
            LocalDate simulationTradeDate,
            AutoMarketRegimePhase regimePhase,
            LocalDateTime modifierWindowStartAt
    ) {
        long seed = ThreadLocalRandom.current().nextLong();
        Random random = new Random(seed);
        return new AutoMarketRegimeModifier(
                config.symbol(),
                simulationTradeDate,
                regimePhase,
                modifierWindowStartAt,
                samplePressures(random, config.secondaryDistributionBias()),
                seed
        );
    }

    private AutoMarketPressure samplePressures(Random random, AutoMarketDistributionBias bias) {
        AutoMarketDistributionBias resolved = bias == null ? AutoMarketDistributionBias.NEUTRAL : bias;
        return new AutoMarketPressure(
                sample(random, resolved.pricePressure()),
                sample(random, resolved.assetPreferencePressure()),
                sample(random, resolved.volatilityPressure()),
                sample(random, resolved.liquidityPressure()),
                sample(random, resolved.executionAggressionPressure())
        );
    }

    private AutoMarketRegimePhase resolveRegimePhase(LocalDateTime now) {
        return AutoMarketRegimePhase.from(now == null ? null : now.toLocalTime());
    }

    private LocalDateTime modifierWindowStartAt(LocalDateTime now) {
        if (now == null) {
            now = LocalDateTime.now();
        }
        int minute = now.getMinute() < 30 ? 0 : 30;
        return now.withMinute(minute).withSecond(0).withNano(0);
    }
}
