package stock.batch.service.batch.common.policy;

import org.springframework.util.StringUtils;

final class BatchJobNames {

    static final String REQUIRED_MESSAGE = "jobName is required";

    private BatchJobNames() {
    }

    static String normalize(String jobName) {
        if (!StringUtils.hasText(jobName)) {
            throw new IllegalArgumentException(REQUIRED_MESSAGE);
        }
        return jobName.trim();
    }
}
