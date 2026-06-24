package stock.batch.service.batch.common.policy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Component;

@Component
public class BatchJobLockRegistry {

    private final Map<String, ReentrantLock> jobLocks = new ConcurrentHashMap<>();

    public ReentrantLock lockFor(String jobName) {
        return jobLocks.computeIfAbsent(jobName, ignored -> new ReentrantLock());
    }
}
