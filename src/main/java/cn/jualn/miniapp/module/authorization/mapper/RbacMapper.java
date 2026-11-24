package cn.jualn.miniapp.module.authorization.mapper;


import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

import cn.jualn.miniapp.module.authorization.model.RbacModels.*;

/**
 * Authentication-owned read projection of User facts; never writes user_profile.
 */
@Mapper
public interface RbacMapper {
    LocalDateTime now();

    Subject subject(long userId);

    List<Subject> subjects(@Param("users") List<Long> users);

    String candidateDirectoryVersion();

    Subject lockSubject(long userId);

    InitialControlOrigin initialControlGrantOrigin(Assignment assignment);

    List<Definition> permissions();

    List<Definition> roles();

    List<Member> members();

    List<Assignment> assignments(long userId);

    Assignment assignment(String id);

    List<OfflineGrantProof> offlineGrantProofs(@Param("assignments") List<Assignment> assignments);

    List<Assignment> assignmentPage(@Param("userId") long userId, @Param("status") String status,
                                    @Param("roleCode") String roleCode, @Param("namespace") String namespace, @Param("evaluatedAt") LocalDateTime evaluatedAt,
                                    @Param("highTime") LocalDateTime highTime, @Param("highId") String highId,
                                    @Param("lastTime") LocalDateTime lastTime, @Param("lastId") String lastId, @Param("limit") int limit);

    long auditHighWater();

    List<Change> changePage(@Param("actor") Long actor, @Param("target") Long target, @Param("assignment") String assignment,
                            @Param("action") String action, @Param("after") LocalDateTime after, @Param("before") LocalDateTime before,
                            @Param("highWater") long highWater, @Param("lastTime") LocalDateTime lastTime,
                            @Param("lastId") String lastId, @Param("limit") int limit);

    List<Assignment> currentAssignments(@Param("userId") long userId, @Param("now") LocalDateTime now);

    String lockGovernance();

    int releaseExpired(@Param("userId") long userId, @Param("now") LocalDateTime now);

    int insertAssignment(Assignment assignment);

    int revoke(@Param("id") String id, @Param("version") String version, @Param("nextVersion") String nextVersion,
               @Param("changeId") String changeId, @Param("now") LocalDateTime now);

    int revise(@Param("userId") long userId, @Param("version") String version,
               @Param("nextVersion") String nextVersion, @Param("now") LocalDateTime now);

    Change receipt(@Param("actor") long actor, @Param("key") byte[] key);

    int insertChange(Change change);

    List<Long> rootUsers();

    int initializeAssignment(@Param("userId") long userId, @Param("version") String version);

    int backfillLegacy(@Param("version") String version);

    long initializedInBatch(String version);

    AuthorityCoverage coverage();
}
