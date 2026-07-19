package stock.batch.service.automarket.biz;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoMarketDailyRegime;
import stock.batch.service.batch.automarket.model.AutoMarketDailyRegimePreparationConfig;
import stock.batch.service.batch.automarket.model.AutoMarketDistributionBias;
import stock.batch.service.batch.automarket.model.AutoMarketPressure;
import stock.batch.service.batch.automarket.model.AutoMarketRegimePhase;
import stock.batch.service.batch.automarket.model.AutoMarketRegimeModifier;
import stock.batch.service.batch.automarket.model.AutoMarketRegimeCountWeights;

import static stock.batch.service.automarket.support.AutoMarketPressureSampler.sample;

@Component
class AutoMarketDailyRegimeService {

    private static final int REGIME_WRITE_ROW_CHUNK_SIZE = 500;
    private static final int REGIME_QUERY_SYMBOL_CHUNK_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;
    private final boolean mysql;

    AutoMarketDailyRegimeService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        String productName = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName()
        );
        this.mysql = productName != null && productName.toLowerCase(Locale.ROOT).contains("mysql");
    }

    List<AutoMarketConfig> applyDailyRegimes(
            List<AutoMarketConfig> configs,
            LocalDate simulationTradeDate,
            LocalDateTime now
    ) {
        if (configs.isEmpty()) {
            return List.of();
        }
        AutoMarketRegimePhase regimePhase = resolveRegimePhase(now);
        DailyRegimeLoad dailyRegimeLoad = ensureDailyRegimesAndLoad(
                configs,
                simulationTradeDate,
                now,
                regimePhase
        );
        LocalDateTime modifierWindowStartAt = modifierWindowStartAt(now);
        RegimeModifierLoad modifierLoad = ensureRegimeModifiersAndLoad(
                configs,
                simulationTradeDate,
                now,
                regimePhase,
                modifierWindowStartAt
        );
        return configs.stream()
                .map(config -> config.withDailyRegime(dailyRegimeLoad.regimesBySymbol().get(config.symbol()))
                        .withRegimeModifier(modifierLoad.modifiersBySymbol().get(config.symbol())))
                .toList();
    }

    int ensureDailyRegimes(
            List<AutoMarketConfig> configs,
            LocalDate simulationTradeDate,
            LocalDateTime now
    ) {
        return ensureDailyRegimes(configs, simulationTradeDate, now, resolveRegimePhase(now));
    }

    int ensureFullDayDailyRegimes(
            List<AutoMarketDailyRegimePreparationConfig> configs,
            LocalDate simulationTradeDate,
            LocalDateTime now
    ) {
        if (configs.isEmpty()) {
            return 0;
        }
        Map<String, AutoMarketDailyRegimePreparationConfig> configsBySymbol = distinctPreparationConfigsBySymbol(configs);
        List<String> symbols = List.copyOf(configsBySymbol.keySet());
        Map<String, Map<AutoMarketRegimePhase, AutoMarketDailyRegime>> existing = findFullDayDailyRegimes(
                symbols,
                simulationTradeDate
        );
        List<AutoMarketDailyRegime> missing = new ArrayList<>();
        for (AutoMarketDailyRegimePreparationConfig config : configsBySymbol.values()) {
            appendMissingFullDayRegimes(
                    config,
                    simulationTradeDate,
                    existing.getOrDefault(config.symbol(), Map.of()),
                    missing
            );
        }
        int createdCount = missing.isEmpty() ? 0 : insertDailyRegimes(missing, now);
        Map<String, Map<AutoMarketRegimePhase, AutoMarketDailyRegime>> loaded = findFullDayDailyRegimes(
                symbols,
                simulationTradeDate
        );
        requireFullDayRegimesLoaded(symbols, loaded);
        return createdCount;
    }

    private int ensureDailyRegimes(
            List<AutoMarketConfig> configs,
            LocalDate simulationTradeDate,
            LocalDateTime now,
            AutoMarketRegimePhase regimePhase
    ) {
        return ensureDailyRegimesAndLoad(configs, simulationTradeDate, now, regimePhase).createdCount();
    }

    private DailyRegimeLoad ensureDailyRegimesAndLoad(
            List<AutoMarketConfig> configs,
            LocalDate simulationTradeDate,
            LocalDateTime now,
            AutoMarketRegimePhase regimePhase
    ) {
        Map<String, AutoMarketConfig> configsBySymbol = distinctConfigsBySymbol(configs);
        List<String> symbols = List.copyOf(configsBySymbol.keySet());
        Map<String, AutoMarketDailyRegime> existing = findDailyRegimes(symbols, simulationTradeDate, regimePhase);
        List<AutoMarketDailyRegime> missing = configsBySymbol.values().stream()
                .filter(config -> !existing.containsKey(config.symbol()))
                .map(config -> createRandomRegime(config, simulationTradeDate, regimePhase))
                .toList();
        if (missing.isEmpty()) {
            return new DailyRegimeLoad(0, existing);
        }
        int createdCount = insertDailyRegimes(missing, now);
        Map<String, AutoMarketDailyRegime> loaded = findDailyRegimes(symbols, simulationTradeDate, regimePhase);
        requireAllSymbolsLoaded("daily regime", symbols, loaded.keySet());
        return new DailyRegimeLoad(createdCount, loaded);
    }

    private Map<String, AutoMarketDailyRegime> findDailyRegimes(
            List<String> symbols,
            LocalDate simulationTradeDate,
            AutoMarketRegimePhase regimePhase
    ) {
        if (symbols.isEmpty()) {
            return Map.of();
        }
        Map<String, AutoMarketDailyRegime> regimesBySymbol = new LinkedHashMap<>();
        for (int start = 0; start < symbols.size(); start += REGIME_QUERY_SYMBOL_CHUNK_SIZE) {
            int end = Math.min(symbols.size(), start + REGIME_QUERY_SYMBOL_CHUNK_SIZE);
            regimesBySymbol.putAll(findDailyRegimeChunk(
                    symbols.subList(start, end),
                    simulationTradeDate,
                    regimePhase
            ));
        }
        return Map.copyOf(regimesBySymbol);
    }

    private Map<String, AutoMarketDailyRegime> findDailyRegimeChunk(
            List<String> symbols,
            LocalDate simulationTradeDate,
            AutoMarketRegimePhase regimePhase
    ) {
        return JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate))
                .sql(
                        """
                        select symbol,
                               simulation_trade_date,
                               regime_phase,
                               source_regime_phase,
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
                .query((rs, rowNum) -> mapDailyRegime(rs))
                .list()
                .stream()
                .collect(LinkedHashMap::new, (map, regime) -> map.put(regime.symbol(), regime), LinkedHashMap::putAll);
    }

    private Map<String, Map<AutoMarketRegimePhase, AutoMarketDailyRegime>> findFullDayDailyRegimes(
            List<String> symbols,
            LocalDate simulationTradeDate
    ) {
        if (symbols.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<AutoMarketRegimePhase, AutoMarketDailyRegime>> regimesBySymbol = new LinkedHashMap<>();
        for (int start = 0; start < symbols.size(); start += REGIME_QUERY_SYMBOL_CHUNK_SIZE) {
            int end = Math.min(symbols.size(), start + REGIME_QUERY_SYMBOL_CHUNK_SIZE);
            List<AutoMarketDailyRegime> rows = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate))
                    .sql(
                            """
                            select symbol,
                                   simulation_trade_date,
                                   regime_phase,
                                   source_regime_phase,
                                   price_pressure,
                                   asset_preference_pressure,
                                   volatility_pressure,
                                   liquidity_pressure,
                                   execution_aggression_pressure,
                                   seed
                              from stock_order_book_daily_regime
                             where symbol in (:symbols)
                               and simulation_trade_date = :simulationTradeDate
                             order by symbol asc, regime_phase asc
                            """
                    )
                    .param("symbols", symbols.subList(start, end))
                    .param("simulationTradeDate", simulationTradeDate)
                    .query((rs, rowNum) -> mapDailyRegime(rs))
                    .list();
            for (AutoMarketDailyRegime row : rows) {
                regimesBySymbol.computeIfAbsent(row.symbol(), ignored -> new EnumMap<>(AutoMarketRegimePhase.class))
                        .put(row.regimePhase(), row);
            }
        }
        return regimesBySymbol;
    }

    private AutoMarketDailyRegime mapDailyRegime(ResultSet rs) throws SQLException {
        AutoMarketRegimePhase regimePhase = AutoMarketRegimePhase.parseOrDefault(rs.getString("regime_phase"));
        String sourcePhaseValue = rs.getString("source_regime_phase");
        AutoMarketRegimePhase sourceRegimePhase = sourcePhaseValue == null || sourcePhaseValue.isBlank()
                ? regimePhase
                : AutoMarketRegimePhase.parseOrDefault(sourcePhaseValue);
        return new AutoMarketDailyRegime(
                rs.getString("symbol"),
                rs.getDate("simulation_trade_date").toLocalDate(),
                regimePhase,
                sourceRegimePhase,
                new AutoMarketPressure(
                        rs.getInt("price_pressure"),
                        rs.getInt("asset_preference_pressure"),
                        rs.getInt("volatility_pressure"),
                        rs.getInt("liquidity_pressure"),
                        rs.getInt("execution_aggression_pressure")
                ),
                rs.getLong("seed")
        );
    }

    private int insertDailyRegimes(List<AutoMarketDailyRegime> regimes, LocalDateTime now) {
        int inserted = 0;
        for (int start = 0; start < regimes.size(); start += REGIME_WRITE_ROW_CHUNK_SIZE) {
            int end = Math.min(regimes.size(), start + REGIME_WRITE_ROW_CHUNK_SIZE);
            List<AutoMarketDailyRegime> chunk = regimes.subList(start, end);
            String placeholders = IntStream.range(0, chunk.size())
                    .mapToObj(index -> "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .collect(java.util.stream.Collectors.joining(", "));
            String sql = mysql
                    ? """
                      insert ignore into stock_order_book_daily_regime(
                          symbol, simulation_trade_date, regime_phase, source_regime_phase,
                          price_pressure, asset_preference_pressure,
                          volatility_pressure, liquidity_pressure, execution_aggression_pressure, seed,
                          created_at, updated_at
                      ) values %s
                      """.formatted(placeholders)
                    : """
                      merge into stock_order_book_daily_regime(
                          symbol, simulation_trade_date, regime_phase, source_regime_phase,
                          price_pressure, asset_preference_pressure,
                          volatility_pressure, liquidity_pressure, execution_aggression_pressure, seed,
                          created_at, updated_at
                      ) key(symbol, simulation_trade_date, regime_phase) values %s
                      """.formatted(placeholders);
            List<Object> parameters = new ArrayList<>(chunk.size() * 12);
            for (AutoMarketDailyRegime regime : chunk) {
                parameters.add(regime.symbol());
                parameters.add(Date.valueOf(regime.simulationTradeDate()));
                parameters.add(regime.regimePhase().name());
                parameters.add(regime.sourceRegimePhase().name());
                parameters.add(regime.pressure().price());
                parameters.add(regime.pressure().assetPreference());
                parameters.add(regime.pressure().volatility());
                parameters.add(regime.pressure().liquidity());
                parameters.add(regime.pressure().executionAggression());
                parameters.add(regime.seed());
                parameters.add(now);
                parameters.add(now);
            }
            inserted += jdbcTemplate.update(sql, parameters.toArray());
        }
        return inserted;
    }

    private AutoMarketDailyRegime createRandomRegime(
            AutoMarketConfig config,
            LocalDate simulationTradeDate,
            AutoMarketRegimePhase regimePhase
    ) {
        return createRandomRegime(
                config.symbol(),
                config.primaryDistributionBias(),
                simulationTradeDate,
                regimePhase
        );
    }

    private AutoMarketDailyRegime createRandomRegime(
            String symbol,
            AutoMarketDistributionBias distributionBias,
            LocalDate simulationTradeDate,
            AutoMarketRegimePhase regimePhase
    ) {
        long seed = ThreadLocalRandom.current().nextLong();
        Random random = new Random(seed);
        return new AutoMarketDailyRegime(
                symbol,
                simulationTradeDate,
                regimePhase,
                regimePhase,
                samplePressures(random, distributionBias),
                seed
        );
    }

    private void appendMissingFullDayRegimes(
            AutoMarketDailyRegimePreparationConfig config,
            LocalDate simulationTradeDate,
            Map<AutoMarketRegimePhase, AutoMarketDailyRegime> existing,
            List<AutoMarketDailyRegime> missing
    ) {
        Set<AutoMarketRegimePhase> refreshPhases = selectRefreshPhases(config.countWeights());
        AutoMarketDailyRegime effectiveRegime = null;
        for (AutoMarketRegimePhase phase : AutoMarketRegimePhase.values()) {
            AutoMarketDailyRegime existingRegime = existing.get(phase);
            if (existingRegime != null) {
                effectiveRegime = existingRegime;
                continue;
            }
            AutoMarketDailyRegime newRegime;
            if (effectiveRegime == null || refreshPhases.contains(phase)) {
                newRegime = createRandomRegime(
                        config.symbol(),
                        config.primaryDistributionBias(),
                        simulationTradeDate,
                        phase
                );
            } else {
                newRegime = new AutoMarketDailyRegime(
                        config.symbol(),
                        simulationTradeDate,
                        phase,
                        effectiveRegime.sourceRegimePhase(),
                        effectiveRegime.pressure(),
                        effectiveRegime.seed()
                );
            }
            missing.add(newRegime);
            effectiveRegime = newRegime;
        }
    }

    private Set<AutoMarketRegimePhase> selectRefreshPhases(AutoMarketRegimeCountWeights weights) {
        Random random = new Random(ThreadLocalRandom.current().nextLong());
        int selectedCount = weights.selectCount(random);
        List<AutoMarketRegimePhase> optionalPhases = new ArrayList<>(List.of(
                AutoMarketRegimePhase.SLOT_0900,
                AutoMarketRegimePhase.SLOT_1200,
                AutoMarketRegimePhase.SLOT_1500
        ));
        Collections.shuffle(optionalPhases, random);
        Set<AutoMarketRegimePhase> selected = EnumSet.of(AutoMarketRegimePhase.SLOT_0600);
        selected.addAll(optionalPhases.subList(0, selectedCount - 1));
        return selected;
    }

    private RegimeModifierLoad ensureRegimeModifiersAndLoad(
            List<AutoMarketConfig> configs,
            LocalDate simulationTradeDate,
            LocalDateTime now,
            AutoMarketRegimePhase regimePhase,
            LocalDateTime modifierWindowStartAt
    ) {
        Map<String, AutoMarketConfig> configsBySymbol = distinctConfigsBySymbol(configs);
        List<String> symbols = List.copyOf(configsBySymbol.keySet());
        Map<String, AutoMarketRegimeModifier> existing = findRegimeModifiers(
                symbols,
                simulationTradeDate,
                regimePhase,
                modifierWindowStartAt
        );
        List<AutoMarketRegimeModifier> missing = configsBySymbol.values().stream()
                .filter(config -> !existing.containsKey(config.symbol()))
                .map(config -> createRandomModifier(
                        config,
                        simulationTradeDate,
                        regimePhase,
                        modifierWindowStartAt
                ))
                .toList();
        if (missing.isEmpty()) {
            return new RegimeModifierLoad(existing);
        }
        insertRegimeModifiers(missing, now);
        Map<String, AutoMarketRegimeModifier> loaded = findRegimeModifiers(
                symbols,
                simulationTradeDate,
                regimePhase,
                modifierWindowStartAt
        );
        requireAllSymbolsLoaded("regime modifier", symbols, loaded.keySet());
        return new RegimeModifierLoad(loaded);
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
        Map<String, AutoMarketRegimeModifier> modifiersBySymbol = new LinkedHashMap<>();
        for (int start = 0; start < symbols.size(); start += REGIME_QUERY_SYMBOL_CHUNK_SIZE) {
            int end = Math.min(symbols.size(), start + REGIME_QUERY_SYMBOL_CHUNK_SIZE);
            modifiersBySymbol.putAll(findRegimeModifierChunk(
                    symbols.subList(start, end),
                    simulationTradeDate,
                    regimePhase,
                    modifierWindowStartAt
            ));
        }
        return Map.copyOf(modifiersBySymbol);
    }

    private Map<String, AutoMarketRegimeModifier> findRegimeModifierChunk(
            List<String> symbols,
            LocalDate simulationTradeDate,
            AutoMarketRegimePhase regimePhase,
            LocalDateTime modifierWindowStartAt
    ) {
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

    private int insertRegimeModifiers(List<AutoMarketRegimeModifier> modifiers, LocalDateTime now) {
        int inserted = 0;
        for (int start = 0; start < modifiers.size(); start += REGIME_WRITE_ROW_CHUNK_SIZE) {
            int end = Math.min(modifiers.size(), start + REGIME_WRITE_ROW_CHUNK_SIZE);
            List<AutoMarketRegimeModifier> chunk = modifiers.subList(start, end);
            String placeholders = IntStream.range(0, chunk.size())
                    .mapToObj(index -> "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .collect(java.util.stream.Collectors.joining(", "));
            String sql = mysql
                    ? """
                      insert ignore into stock_order_book_regime_modifier(
                          symbol, simulation_trade_date, regime_phase, modifier_window_start_at,
                          price_pressure, asset_preference_pressure, volatility_pressure,
                          liquidity_pressure, execution_aggression_pressure, seed, created_at, updated_at
                      ) values %s
                      """.formatted(placeholders)
                    : """
                      merge into stock_order_book_regime_modifier(
                          symbol, simulation_trade_date, regime_phase, modifier_window_start_at,
                          price_pressure, asset_preference_pressure, volatility_pressure,
                          liquidity_pressure, execution_aggression_pressure, seed, created_at, updated_at
                      ) key(symbol, simulation_trade_date, regime_phase, modifier_window_start_at) values %s
                      """.formatted(placeholders);
            List<Object> parameters = new ArrayList<>(chunk.size() * 12);
            for (AutoMarketRegimeModifier modifier : chunk) {
                parameters.add(modifier.symbol());
                parameters.add(Date.valueOf(modifier.simulationTradeDate()));
                parameters.add(modifier.regimePhase().name());
                parameters.add(modifier.modifierWindowStartAt());
                parameters.add(modifier.pressure().price());
                parameters.add(modifier.pressure().assetPreference());
                parameters.add(modifier.pressure().volatility());
                parameters.add(modifier.pressure().liquidity());
                parameters.add(modifier.pressure().executionAggression());
                parameters.add(modifier.seed());
                parameters.add(now);
                parameters.add(now);
            }
            inserted += jdbcTemplate.update(sql, parameters.toArray());
        }
        return inserted;
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

    private Map<String, AutoMarketConfig> distinctConfigsBySymbol(List<AutoMarketConfig> configs) {
        Map<String, AutoMarketConfig> configsBySymbol = new LinkedHashMap<>();
        for (AutoMarketConfig config : configs) {
            if (config.symbol() != null && !config.symbol().isBlank()) {
                configsBySymbol.putIfAbsent(config.symbol(), config);
            }
        }
        return Map.copyOf(configsBySymbol);
    }

    private Map<String, AutoMarketDailyRegimePreparationConfig> distinctPreparationConfigsBySymbol(
            List<AutoMarketDailyRegimePreparationConfig> configs
    ) {
        Map<String, AutoMarketDailyRegimePreparationConfig> configsBySymbol = new LinkedHashMap<>();
        for (AutoMarketDailyRegimePreparationConfig config : configs) {
            if (config.symbol() != null && !config.symbol().isBlank()) {
                configsBySymbol.putIfAbsent(config.symbol(), config);
            }
        }
        return Map.copyOf(configsBySymbol);
    }

    private void requireAllSymbolsLoaded(String valueType, List<String> expectedSymbols, Set<String> loadedSymbols) {
        if (Set.copyOf(expectedSymbols).equals(loadedSymbols)) {
            return;
        }
        throw new IllegalStateException(
                "Auto market %s write did not persist every symbol: expected=%d, loaded=%d"
                        .formatted(valueType, expectedSymbols.size(), loadedSymbols.size())
        );
    }

    private void requireFullDayRegimesLoaded(
            List<String> expectedSymbols,
            Map<String, Map<AutoMarketRegimePhase, AutoMarketDailyRegime>> loaded
    ) {
        long completeSymbolCount = expectedSymbols.stream()
                .filter(symbol -> loaded.getOrDefault(symbol, Map.of()).size() == AutoMarketRegimePhase.values().length)
                .count();
        if (completeSymbolCount == expectedSymbols.size()) {
            return;
        }
        throw new IllegalStateException(
                "Auto market full-day regime write did not persist every phase: expectedSymbols=%d, completeSymbols=%d"
                        .formatted(expectedSymbols.size(), completeSymbolCount)
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

    private record DailyRegimeLoad(
            int createdCount,
            Map<String, AutoMarketDailyRegime> regimesBySymbol
    ) {
    }

    private record RegimeModifierLoad(
            Map<String, AutoMarketRegimeModifier> modifiersBySymbol
    ) {
    }
}
