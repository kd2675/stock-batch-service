package stock.batch.service.automarket.biz;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import stock.batch.service.automarket.profile.AutoProfileBehavior;
import stock.batch.service.automarket.profile.AutoProfileBehaviorRegistry;
import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

@Component
class AutoProfileBehaviorSupport {

    private final AutoProfileBehaviorRegistry profileBehaviors = AutoProfileBehaviorRegistry.createDefault();

    AutoProfileBehavior behavior(AutoParticipantProfileType profileType) {
        return profileBehaviors.behavior(profileType);
    }

    ProfilePolicy defaultPolicy(AutoParticipantProfileType profileType) {
        return behavior(profileType).defaultPolicy();
    }

    ProfilePolicy policy(
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            AutoParticipantProfileType profileType
    ) {
        return profilePolicies.getOrDefault(profileType, profilePolicies.get(AutoParticipantProfileType.defaultType()));
    }

    Map<AutoParticipantProfileType, ProfilePolicy> policiesWithOverrides(List<AutoParticipantProfileConfig> configs) {
        return profileBehaviors.policiesWithOverrides(configs);
    }
}
