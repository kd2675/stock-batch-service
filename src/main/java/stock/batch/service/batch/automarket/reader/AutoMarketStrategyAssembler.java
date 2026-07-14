package stock.batch.service.batch.automarket.reader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;

final class AutoMarketStrategyAssembler {

    private AutoMarketStrategyAssembler() {
    }

    static Map<String, List<AutoParticipantStrategy>> bySymbol(
            List<AutoMarketConfig> configs,
            List<ActiveParticipantStrategy> activeParticipants,
            List<ParticipantSymbolStrategyConfig> symbolConfigs
    ) {
        Map<String, ParticipantSymbolStrategyConfig> symbolConfigByKey = toSymbolConfigMap(symbolConfigs);
        Map<String, List<AutoParticipantStrategy>> strategiesBySymbol = new LinkedHashMap<>();
        for (AutoMarketConfig config : configs) {
            List<AutoParticipantStrategy> strategies = new ArrayList<>();
            for (ActiveParticipantStrategy participant : activeParticipants) {
                ParticipantSymbolStrategyConfig symbolConfig = symbolConfigByKey.get(
                        strategyConfigKey(participant.userKey(), config.symbol())
                );
                if (symbolConfig != null && !symbolConfig.enabled()) {
                    continue;
                }
                int intensity = symbolConfig == null ? 5 : symbolConfig.intensity();
                strategies.add(participant.toStrategy(Math.clamp(intensity, 1, 10)));
            }
            strategiesBySymbol.put(config.symbol(), List.copyOf(strategies));
        }
        return strategiesBySymbol;
    }

    private static Map<String, ParticipantSymbolStrategyConfig> toSymbolConfigMap(
            List<ParticipantSymbolStrategyConfig> symbolConfigs
    ) {
        Map<String, ParticipantSymbolStrategyConfig> byKey = new HashMap<>();
        for (ParticipantSymbolStrategyConfig config : symbolConfigs) {
            byKey.put(strategyConfigKey(config.userKey(), config.symbol()), config);
        }
        return byKey;
    }

    private static String strategyConfigKey(String userKey, String symbol) {
        return userKey + "\n" + AutoMarketReaderMapper.normalizeSymbol(symbol);
    }

}
