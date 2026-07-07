package stock.batch.service.automarket.biz;

import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

import stock.batch.service.batch.automarket.model.AutoMarketAssetPreference;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoMarketDailyRegime;
import stock.batch.service.batch.automarket.model.AutoMarketPriceDirection;
import stock.batch.service.batch.automarket.model.AutoMarketRegimePhase;
import stock.batch.service.batch.automarket.model.AutoMarketRegimeModifier;
import stock.batch.service.simulation.SimulationMarketSessionService;

@Component
@RequiredArgsConstructor
class AutoMarketDailyRegimeService {

    private final JdbcTemplate jdbcTemplate;
    private final SimulationMarketSessionService simulationMarketSessionService;

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

    int ensureOpeningDailyRegimes(
            List<AutoMarketConfig> configs,
            LocalDate simulationTradeDate,
            LocalDateTime now
    ) {
        return ensureDailyRegimes(configs, simulationTradeDate, now, AutoMarketRegimePhase.OPENING);
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
                        symbol, simulation_trade_date, regime_phase, price_direction, asset_preference,
                        direction_intensity, volatility_level, liquidity_level, execution_aggression_level, seed,
                        created_at, updated_at
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    regime.symbol(),
                    Date.valueOf(regime.simulationTradeDate()),
                    regime.regimePhase().name(),
                    regime.priceDirection().name(),
                    regime.assetPreference().name(),
                    regime.directionIntensity(),
                    regime.volatilityLevel(),
                    regime.liquidityLevel(),
                    regime.executionAggressionLevel(),
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
                               price_direction,
                               asset_preference,
                               direction_intensity,
                               volatility_level,
                               liquidity_level,
                               execution_aggression_level,
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
                        AutoMarketPriceDirection.parseOrDefault(rs.getString("price_direction")),
                        AutoMarketAssetPreference.parseOrDefault(rs.getString("asset_preference")),
                        rs.getInt("direction_intensity"),
                        rs.getInt("volatility_level"),
                        rs.getInt("liquidity_level"),
                        rs.getInt("execution_aggression_level"),
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
                pickPriceDirection(random.nextInt(100)),
                pickAssetPreference(random.nextInt(100)),
                Math.clamp(config.intensity(), 1, 10),
                pickBellishLevel(random),
                pickBellishLevel(random),
                pickBellishLevel(random),
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
                        price_direction_modifier, asset_preference_modifier, direction_intensity_modifier,
                        volatility_modifier, liquidity_modifier, execution_aggression_modifier,
                        seed, created_at, updated_at
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    modifier.symbol(),
                    Date.valueOf(modifier.simulationTradeDate()),
                    modifier.regimePhase().name(),
                    modifier.modifierWindowStartAt(),
                    modifier.priceDirectionModifier(),
                    modifier.assetPreferenceModifier(),
                    modifier.directionIntensityModifier(),
                    modifier.volatilityModifier(),
                    modifier.liquidityModifier(),
                    modifier.executionAggressionModifier(),
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
                               price_direction_modifier,
                               asset_preference_modifier,
                               direction_intensity_modifier,
                               volatility_modifier,
                               liquidity_modifier,
                               execution_aggression_modifier,
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
                        rs.getInt("price_direction_modifier"),
                        rs.getInt("asset_preference_modifier"),
                        rs.getInt("direction_intensity_modifier"),
                        rs.getInt("volatility_modifier"),
                        rs.getInt("liquidity_modifier"),
                        rs.getInt("execution_aggression_modifier"),
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
                pickSecondaryPricePressure(random),
                pickSecondaryAssetPressure(random),
                pickBellishLevel(random),
                pickBellishLevel(random),
                pickBellishLevel(random),
                pickBellishLevel(random),
                seed
        );
    }

    private int pickSecondaryPricePressure(Random random) {
        AutoMarketPriceDirection direction = pickPriceDirection(random.nextInt(100));
        if (direction == AutoMarketPriceDirection.NEUTRAL) {
            return 0;
        }
        return (int) direction.pressureSign() * pickBellishLevel(random);
    }

    private int pickSecondaryAssetPressure(Random random) {
        AutoMarketAssetPreference preference = pickAssetPreference(random.nextInt(100));
        if (preference == AutoMarketAssetPreference.BALANCED) {
            return 0;
        }
        return (int) preference.buyPressureSign() * pickBellishLevel(random);
    }

    private AutoMarketPriceDirection pickPriceDirection(int roll) {
        if (roll < 43) {
            return AutoMarketPriceDirection.UP;
        }
        if (roll < 86) {
            return AutoMarketPriceDirection.DOWN;
        }
        return AutoMarketPriceDirection.NEUTRAL;
    }

    private AutoMarketAssetPreference pickAssetPreference(int roll) {
        if (roll < 42) {
            return AutoMarketAssetPreference.STOCK;
        }
        if (roll < 84) {
            return AutoMarketAssetPreference.CASH;
        }
        return AutoMarketAssetPreference.BALANCED;
    }

    private int pickBellishLevel(Random random) {
        int first = random.nextInt(10) + 1;
        int second = random.nextInt(10) + 1;
        return Math.clamp((first + second + 1) / 2, 1, 10);
    }

    private AutoMarketRegimePhase resolveRegimePhase(LocalDateTime now) {
        if (now == null || now.toLocalTime().isBefore(midSessionTime())) {
            return AutoMarketRegimePhase.OPENING;
        }
        return AutoMarketRegimePhase.MIDDAY;
    }

    private LocalTime midSessionTime() {
        LocalTime openTime = simulationMarketSessionService.openTime();
        LocalTime closeTime = simulationMarketSessionService.closeTime();
        long halfSessionSeconds = Duration.between(openTime, closeTime).toSeconds() / 2;
        return openTime.plusSeconds(halfSessionSeconds);
    }

    private LocalDateTime modifierWindowStartAt(LocalDateTime now) {
        if (now == null) {
            now = LocalDateTime.now();
        }
        int minute = now.getMinute() < 30 ? 0 : 30;
        return now.withMinute(minute).withSecond(0).withNano(0);
    }
}
