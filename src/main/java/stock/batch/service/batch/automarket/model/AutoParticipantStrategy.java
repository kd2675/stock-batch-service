package stock.batch.service.batch.automarket.model;

public record AutoParticipantStrategy(
        long accountId,
        int intensity,
        AutoParticipantProfileType profileType
) {
}
