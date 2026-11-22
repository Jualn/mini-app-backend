package cn.jualn.miniapp.module.points.service;

import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;

import cn.jualn.miniapp.module.points.bo.PointsBO.Acceptance;
import cn.jualn.miniapp.module.points.bo.PointsBO.AcceptingOrganizations;
import cn.jualn.miniapp.module.points.bo.PointsBO.Action;
import cn.jualn.miniapp.module.points.bo.PointsBO.Application;
import cn.jualn.miniapp.module.points.bo.PointsBO.Eligibility;
import cn.jualn.miniapp.module.points.bo.PointsBO.Ledger;
import cn.jualn.miniapp.module.points.bo.PointsBO.Management;
import cn.jualn.miniapp.module.points.bo.PointsBO.Query;
import cn.jualn.miniapp.module.points.bo.PointsBO.Subject;

import java.util.List;

/** Owner boundaries, not alternative identity, storage or notification systems. */
public interface PointsDependencies {
    default Subject subject(long user) {throw cn.jualn.miniapp.module.points.PointsProblems.unavailable(cn.jualn.miniapp.module.points.PointsProblems.Dependency.PERSON_RESOLVER);}
    default boolean self(long user,String person) {throw cn.jualn.miniapp.module.points.PointsProblems.unavailable(cn.jualn.miniapp.module.points.PointsProblems.Dependency.PERSON_RESOLVER);}
    default boolean approvalEligiblePerson(String person) {throw cn.jualn.miniapp.module.points.PointsProblems.unavailable(cn.jualn.miniapp.module.points.PointsProblems.Dependency.PERSON_RESOLVER);}
    default void requirePersonManage(long actor,String person,Action action) {throw cn.jualn.miniapp.module.points.PointsProblems.unavailable(cn.jualn.miniapp.module.points.PointsProblems.Dependency.AUTHORIZATION);}
    default void finishPerson(Action action,long actor,String person) {throw cn.jualn.miniapp.module.points.PointsProblems.unavailable(cn.jualn.miniapp.module.points.PointsProblems.Dependency.AUTHORIZATION);}
    default Management management(long actor,Application application) {return null;}
    default String organizationName(String organization) {return null;}
    default PointsAcceptancePolicy.Plan organizationPlan(long actor,String view) {throw cn.jualn.miniapp.module.points.PointsProblems.unavailable(cn.jualn.miniapp.module.points.PointsProblems.Dependency.AUTHORIZATION);}
    long actor(Action action);
    /** First local transaction operation: person gate, then stable User/RBAC owner locks. */
    default void begin(Action action,long actor,long... targets) {}
    /** Recheck time-sensitive authority immediately before committing a new business effect. */
    default void finish(Action action,long actor,long userId,boolean eligibilityRequired) {}
    default void audit(Action action,long actor,long userId,long applicationId,Long ledgerId,String key) {}
    default java.time.LocalDateTime evaluatedAt(java.time.LocalDateTime fallback) { return fallback; }
    default cn.jualn.miniapp.module.person.service.facts.PersonFactReaders.PointsQuery query(long actor,String personId,String organizationId) {
        throw cn.jualn.miniapp.module.points.PointsProblems.unavailable(cn.jualn.miniapp.module.points.PointsProblems.Dependency.PERSON_RESOLVER);
    }
    boolean eligible(long userId);
    default void finishAdmission(long user) {finish(Action.CREATE,user,user,true);if(!eligible(user)) throw cn.jualn.miniapp.module.points.PointsProblems.forbidden("ineligible");}
    default Eligibility eligibility(long user,java.time.LocalDateTime at) {return new Eligibility(eligible(user),at);}
    default AcceptingOrganizations acceptingOrganizations(long user) {throw cn.jualn.miniapp.module.points.PointsProblems.unavailable(cn.jualn.miniapp.module.points.PointsProblems.Dependency.PERSON_RESOLVER);}
    default Acceptance acceptance(long user,String organization) {throw cn.jualn.miniapp.module.points.PointsProblems.unavailable(cn.jualn.miniapp.module.points.PointsProblems.Dependency.BUSINESS_ELIGIBILITY);}
    default void requireApplication(long actor,Application application,Action action) {throw cn.jualn.miniapp.module.points.PointsProblems.unavailable(cn.jualn.miniapp.module.points.PointsProblems.Dependency.AUTHORIZATION);}
    default void finishApplication(Action action,long actor,Application application,boolean eligibilityRequired) {throw cn.jualn.miniapp.module.points.PointsProblems.unavailable(cn.jualn.miniapp.module.points.PointsProblems.Dependency.AUTHORIZATION);}
    default void recheckAcceptance(Application application) {throw cn.jualn.miniapp.module.points.PointsProblems.unavailable(cn.jualn.miniapp.module.points.PointsProblems.Dependency.BUSINESS_ELIGIBILITY);}
    default PointsAcceptancePolicy.Plan applicationPlan(long actor,String person,String organization,Query query) {throw cn.jualn.miniapp.module.points.PointsProblems.unavailable(cn.jualn.miniapp.module.points.PointsProblems.Dependency.AUTHORIZATION);}
    default PointsAcceptancePolicy.Plan personPlan(long actor,String person,String organization) {throw cn.jualn.miniapp.module.points.PointsProblems.unavailable(cn.jualn.miniapp.module.points.PointsProblems.Dependency.AUTHORIZATION);}
    default PointsAcceptancePolicy.Plan statisticsPlan(long actor,String organization,boolean accepting) {throw cn.jualn.miniapp.module.points.PointsProblems.unavailable(cn.jualn.miniapp.module.points.PointsProblems.Dependency.AUTHORIZATION);}
    /** Local DB retention/binding must join the application transaction; no remote IO here. */
    void bindFiles(long userId,long applicationId,List<String> fileIds);
    /** Validate currently retained, safe and controlled evidence, without public URL fallback. */
    void requireFiles(Application application);
    /** Accept a durable local notification intent in the SAME transaction. Never deliver remotely here. */
    void acceptResult(Application application,Ledger reversal);
    default void acceptCorrection(Application application,cn.jualn.miniapp.module.points.bo.PointsBO.Correction correction) {throw cn.jualn.miniapp.module.points.PointsProblems.unavailable(cn.jualn.miniapp.module.points.PointsProblems.Dependency.NOTIFICATION_ACCEPTANCE);}
}
