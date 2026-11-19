package cn.jualn.miniapp.module.post.service;

import java.time.OffsetDateTime;
import java.util.List;

/** Current Person facts supplied by the Person owner, never inferred from the Post author. */
public interface GrowthQualification {
    record Organization(String organizationId, String name, List<String> allowedRecordTypes) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record Context(String personId, OffsetDateTime evaluatedAt, List<Organization> organizations) {}
    Context context(long actor);
    String currentPerson(long actor);
    boolean member(long actor, String organization);
    boolean organizationActive(String organization);
    String requirePublisher(long actor, String organization, String type);
    void guard(long actor);
}
