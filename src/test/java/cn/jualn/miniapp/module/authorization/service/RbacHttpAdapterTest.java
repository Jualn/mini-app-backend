package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.module.authorization.model.RbacModels;
import cn.jualn.miniapp.module.authorization.support.RbacTime;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RbacHttpAdapterTest {
    @Test void publicExpiryValidatesRawPrecisionAndNormalizesTheInstantWithoutRounding() {
        assertNull(RbacTime.expiry(null));
        for(int digits=0;digits<=6;digits++) {
            String fraction=digits==0?"":"."+"123456".substring(0,digits);
            var expected=LocalDateTime.parse("2026-11-15T02:00:00"+fraction);
            assertEquals(expected,RbacTime.expiry("2026-11-15T02:00:00"+fraction+"Z"));
            assertEquals(expected,RbacTime.expiry("2026-11-15T10:00:00"+fraction+"+08:00"));
            assertEquals(expected,RbacTime.expiry("2026-11-14T21:00:00"+fraction+"-05:00"));
        }
        assertEquals(RbacTime.expiry("2026-11-15T02:00:00.1Z"),RbacTime.expiry("2026-11-15t02:00:00.100000z"));
        assertEquals(LocalDateTime.parse("2026-11-14T02:01:00"),RbacTime.expiry("2026-11-15T02:00:00+23:59"));
        assertNotEquals(RbacTime.expiry("2026-11-15T02:00:00.123456Z"),RbacTime.expiry("2026-11-15T02:00:00.123457Z"));
        for(String value:new String[]{"", "2026-11-15T02:00:00.1234567Z", "2026-11-15T02:00:00.123456000Z",
                "2026-11-15T02:00:00.0000000Z", "2026-11-15T02:00:00.123456789123456789Z",
                "2026-11-15T02:00:00", "2026-02-29T02:00:00Z", "2026-11-15T24:00:00Z",
                "2026-11-15T02:00:00.Z", "2026-11-15T02:00:00+24:00", "2026-11-15T02:00:00+08:60",
                "0999-12-31T23:59:59Z", "1000-01-01T00:00:00+00:01", "9999-12-31T23:59:59-00:01",
                "+10000-01-01T00:00:00Z"}) {
            var e=assertThrows(ContractProblemException.class,()->RbacTime.expiry(value),value);
            assertTrue(e.getMessage()!=null);
        }
        assertEquals("9999-12-31T23:59:59.999999Z",RbacTime.wire(RbacTime.expiry("9999-12-31T23:59:59.999999Z")));
    }
    @Test void offsetQueriesAreEquivalentAndArbitraryFractionsHaveExactMicrosecondPredicates() {
        assertEquals(RbacTime.queryBoundary("2026-10-16T02:00:00.123456Z"),RbacTime.queryBoundary("2026-10-16T10:00:00.123456+08:00"));
        assertEquals(LocalDateTime.parse("2026-10-16T02:00:00.123457"),RbacTime.queryBoundary("2026-10-16T02:00:00.1234560000000001Z"));
        assertEquals(LocalDateTime.parse("2026-10-16T02:00:00.123456"),RbacTime.queryBoundary("2026-10-16T02:00:00.1234560000000000Z"));
        assertDoesNotThrow(()->RbacTime.range("2026-10-16T02:00:00.00000001Z","2026-10-16T02:00:00.00000002Z"));
        assertThrows(ContractProblemException.class,()->RbacTime.range("2026-10-16T02:00:00Z","2026-10-16T10:00:00+08:00"));
        assertThrows(ContractProblemException.class,()->RbacTime.queryBoundary("2026-10-16 02:00:00"));
    }
    @Test void internalSnapshotsKeepMicrosecondsAndReadExistingV1SecondRepresentations() throws Exception {
        ObjectMapper mapper=RbacJson.mapper();var a=new RbacModels.Assignment();a.setId("assignment");a.setUserId(43L);
        a.setGrantedAt(LocalDateTime.parse("2026-10-16T02:00:00.123456"));
        var first=new RbacModels.Receipt("c","op","GRANT",a.getGrantedAt(),"opaque",a);
        String snapshot=mapper.writeValueAsString(first);assertTrue(snapshot.contains(".123456"));
        assertEquals(first,mapper.readValue(snapshot,RbacModels.Receipt.class));
        assertEquals(LocalDateTime.parse("2026-10-16T02:00:00"),mapper.readValue("{\"grantedAt\":\"2026-10-16 02:00:00\"}",RbacModels.Assignment.class).getGrantedAt());
    }
    @Test void continuationIntegrityRejectsTamperingAndAnotherProcessKey() {
        var codec=new RbacCursorCodec();var anchor=new RbacCursorCodec.Anchor(1,"audit",42,"query-and-versions","2026-10-16T02:00:00",null,null,9,"2026-10-16T01:00:00","last");
        String cursor=codec.encode(anchor);assertEquals(anchor,codec.decode(cursor));
        assertThrows(ContractProblemException.class,()->codec.decode(cursor+"x"));
        assertThrows(ContractProblemException.class,()->new RbacCursorCodec().decode(cursor));
    }
}
