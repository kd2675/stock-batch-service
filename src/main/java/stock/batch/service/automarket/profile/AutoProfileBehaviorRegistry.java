package stock.batch.service.automarket.profile;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class AutoProfileBehaviorRegistry {

    private final Map<AutoParticipantProfileType, AutoProfileBehavior> behaviors;

    private AutoProfileBehaviorRegistry(List<AutoProfileBehavior> behaviors) {
        Map<AutoParticipantProfileType, AutoProfileBehavior> mappedBehaviors = new EnumMap<>(AutoParticipantProfileType.class);
        for (AutoProfileBehavior behavior : behaviors) {
            mappedBehaviors.put(behavior.type(), behavior);
        }
        this.behaviors = Map.copyOf(mappedBehaviors);
    }

    public static AutoProfileBehaviorRegistry createDefault() {
        return new AutoProfileBehaviorRegistry(List.of(
                new NewsReactiveBehavior(),
                new MomentumFollowerBehavior(),
                new ContrarianBehavior(),
                new LossAverseBehavior(),
                new OverconfidentBehavior(),
                new HerdFollowerBehavior(),
                new MarketMakerBehavior(),
                new NoiseTraderBehavior(),
                new ValueAnchorBehavior(),
                new ScalperBehavior(),
                new DayTraderBehavior(),
                new SwingTraderBehavior(),
                new LongTermHolderBehavior(),
                new PaydayAccumulatorBehavior(),
                new DividendReinvestorBehavior(),
                new LimitDownTrappedBehavior(),
                new AverageDownBuyerBehavior(),
                new StopLossTraderBehavior(),
                new FomoBuyerBehavior(),
                new PanicSellerBehavior(),
                new DipBuyerBehavior(),
                new ProfitLockerBehavior(),
                new LiquidityAvoidantBehavior(),
                new CashDefensiveBehavior(),
                new WhaleBehavior(),
                new SmallDiversifierBehavior(),
                new ObserverBehavior()
        ));
    }

    public AutoProfileBehavior behavior(AutoParticipantProfileType profileType) {
        return behaviors.getOrDefault(profileType, behaviors.get(AutoParticipantProfileType.defaultType()));
    }

    public Map<AutoParticipantProfileType, ProfilePolicy> defaultPolicies() {
        Map<AutoParticipantProfileType, ProfilePolicy> policies = new EnumMap<>(AutoParticipantProfileType.class);
        for (AutoProfileBehavior behavior : behaviors.values()) {
            policies.put(behavior.type(), behavior.defaultPolicy());
        }
        return policies;
    }

    public Map<AutoParticipantProfileType, ProfilePolicy> policiesWithOverrides(List<AutoParticipantProfileConfig> configs) {
        Map<AutoParticipantProfileType, ProfilePolicy> policies = new EnumMap<>(defaultPolicies());
        for (AutoParticipantProfileConfig config : configs) {
            ProfilePolicy current = policies.getOrDefault(config.profileType(), behavior(config.profileType()).defaultPolicy());
            policies.put(config.profileType(), current.overrideWith(config));
        }
        return Map.copyOf(policies);
    }
}
