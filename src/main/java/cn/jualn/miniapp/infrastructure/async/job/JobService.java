package cn.jualn.miniapp.infrastructure.async.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class JobService {
    private final AsyncJobMapper mapper;
    private final ObjectMapper objectMapper;

    public JobService(AsyncJobMapper mapper,
                      @Qualifier("objectMapper") ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long create(JobDefinition definition) {
        validate(definition);
        AsyncJob job = new AsyncJob();
        job.setJobType(definition.jobType());
        job.setSchemaVersion(definition.schemaVersion());
        job.setOperationId(definition.operationId());
        job.setDedupeKey(definition.dedupeKey());
        job.setSubjectType(definition.subjectType());
        job.setSubjectId(definition.subjectId());
        job.setPayload(writePayload(definition.payload()));
        job.setNextRunAt(definition.nextRunAt() == null ? LocalDateTime.now() : definition.nextRunAt());
        job.setMaxAttempts(definition.maxAttempts());
        try {
            mapper.insert(job);
            return job.getId();
        } catch (DuplicateKeyException duplicate) {
            if (definition.dedupeKey() == null) {
                throw duplicate;
            }
            AsyncJob existing = mapper.selectByDedupe(definition.jobType(), definition.dedupeKey());
            if (existing == null) {
                throw duplicate;
            }
            return existing.getId();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancelPending(String jobType, String dedupeKey) {
        return mapper.cancelPendingByDedupe(jobType, dedupeKey, LocalDateTime.now()) == 1;
    }

    @Transactional(readOnly = true)
    public AsyncJob inspect(long jobId) {
        return mapper.selectById(jobId);
    }

    @Transactional(readOnly = true)
    public List<AsyncJob> listDead(int limit) {
        return mapper.selectDead(Math.min(Math.max(limit, 1), 100));
    }

    @Transactional
    public boolean manualRetryDead(long jobId) {
        return mapper.retryDead(jobId, LocalDateTime.now()) == 1;
    }

    private void validate(JobDefinition definition) {
        if (definition == null || definition.jobType() == null || definition.jobType().isBlank()
                || definition.schemaVersion() < 1 || definition.payload() == null
                || definition.maxAttempts() < 1) {
            throw new IllegalArgumentException("Invalid async job definition");
        }
    }

    private String writePayload(Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            if (json.length() > 65536) {
                throw new IllegalArgumentException("Async job payload exceeds 64 KiB");
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Async job payload cannot be serialized", exception);
        }
    }
}
