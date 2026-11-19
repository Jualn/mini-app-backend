package cn.jualn.miniapp.module.person.service.support;

import cn.jualn.miniapp.module.person.service.facts.PersonFoundationService;

import cn.jualn.miniapp.module.person.bo.PersonBO.Result;
import cn.jualn.miniapp.module.person.entity.PersonRows;
import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static cn.jualn.miniapp.module.person.PersonProblems.invalid;
import static cn.jualn.miniapp.module.person.PersonProblems.problem;
import static cn.jualn.miniapp.module.person.PersonProblems.unavailable;

import static cn.jualn.miniapp.module.person.service.facts.PersonFoundationService.hash;
import static cn.jualn.miniapp.module.person.service.facts.PersonFoundationService.key;
import static cn.jualn.miniapp.module.person.service.facts.PersonFoundationService.one;
import static cn.jualn.miniapp.module.person.service.facts.PersonFoundationService.reason;

/** Same permanent event/receipt authority as existing Person commands. No raw identifier audit. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ManagedPersonJournal {
    @org.springframework.beans.factory.annotation.Autowired
    private cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal managementAudit;

    private final PersonMapper facts;
    private final ManagedPersonMapper persons;
    private final PersonPrivateData secrets;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw unavailable(); }
    }
    public <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw unavailable(); }
    }
    public String fingerprint(Object value) { return secrets.digest("person-command", encode(value)); }
    public <T> Result<T> replay(long actor, String op, String target, String key, Object command, Class<T> type) {
        commandKey(key);
        var r = facts.receipt(actor, op, target, key);
        if (r == null) return null;
        if (!fingerprint(command).equals(r.getFingerprint())) throw problem(org.springframework.http.HttpStatus.CONFLICT, "person-idempotency-conflict");
        if (r.getStatus() == 202) throw problem(org.springframework.http.HttpStatus.CONFLICT, "person-operation-in-progress");
        if (r.getEventId() == null) {
            if (!"updatePersonMajorClassRegistration".equals(op) || r.getStatus() != 200) throw unavailable();
        } else {
            var e = facts.event(r.getEventId());
            if (e == null || !r.getId().equals(e.getReceiptId())) throw unavailable();
        }
        return new Result<>(decode(r.getResultJson(), type), r.getEtag(), r.getLocation(), r.getStatus());
    }
    public <T> Result<T> result(T value, int status, String location) {
        return new Result<>(value, "\"" + hash(encode(value)) + "\"", location, status);
    }
    public void match(String supplied, Object value) {
        if (supplied == null) throw problem(org.springframework.http.HttpStatus.PRECONDITION_REQUIRED, "precondition-required");
        if (!supplied.matches("\"[^\"\\r\\n]+\"")) throw invalid("If-Match");
        if (!result(value, 200, null).etag().equals(supplied)) throw problem(org.springframework.http.HttpStatus.PRECONDITION_FAILED, "precondition-failed");
    }
    public void event(long actor, String person, String kind, String resource, long revision, String event,
               String operation, String reason, String reference, String before, String after,
               String receipt, LocalDateTime at) {
        var row = new PersonRows.Event();
        row.setId(event); row.setAggregateType(kind); row.setAggregateId(resource); row.setAggregateVersion(revision);
        row.setOperation(operation); row.setAfterJson("{}"); row.setOperatorUserId(actor); row.setRecordedAt(at);
        row.setReason(reason); row.setVerificationReference(reference); row.setReceiptId(receipt);
        row.setTraceId(org.slf4j.MDC.get("traceId"));
        one(persons.insertEvent(row, person, before, after));

    }
    public <T> Result<T> complete(long actor, String op, String target, String key, Object command,
                            String receipt, String event, Result<T> result, LocalDateTime at) {
        // Finish the command's event before its first commit; committed history is never rewritten.
        if (event != null) one(persons.completeEvent(event, receipt, encode(result.value())));
        var row = new PersonRows.Receipt();
        row.setId(receipt); row.setActor(actor); row.setOperation(op); row.setTarget(target); row.setCommandKey(key);
        row.setFingerprint(fingerprint(command)); row.setResultJson(encode(result.value())); row.setEtag(result.etag());
        row.setLocation(result.location()); row.setStatus(result.status()); row.setEventId(event); row.setCreatedAt(at);
        one(facts.insertReceipt(row));
        if(event!=null) {var fact=facts.event(event);managementAudit.operation(receipt,actor,op,fact.getAggregateType(),fact.getAggregateId(),at);}
        return result;
    }
    public static void commandKey(String key) {
        if (key == null || key.codePointCount(0, key.length()) > 200 || key.isBlank()) throw invalid("Idempotency-Key");
    }
}
