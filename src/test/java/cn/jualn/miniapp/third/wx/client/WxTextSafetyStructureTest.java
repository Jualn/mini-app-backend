package cn.jualn.miniapp.third.wx.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WxTextSafetyStructureTest {
    private ObjectNode valid() throws Exception {
        return (ObjectNode) new ObjectMapper().readTree("""
            {"errcode":0,"errmsg":"ok","trace_id":"synthetic",
             "result":{"suggest":"pass","label":100},"detail":[
              {"strategy":"content_model","errcode":0,"suggest":"pass","label":100,"prob":90},
              {"strategy":"keyword","errcode":0}]}
            """);
    }
    private ObjectNode model(ObjectNode body) { return (ObjectNode) body.path("detail").get(0); }
    private ObjectNode keyword(ObjectNode body) { return (ObjectNode) body.path("detail").get(1); }

    @Test void onlyObservedEmptyKeywordShapeNeedsCompatibility() throws Exception {
        var body = valid();
        assertTrue(WxTextSafetyStructure.pass(body, true));
        assertFalse(WxTextSafetyStructure.pass(body, false));
        keyword(body).put("suggest", "pass").put("label", 100);
        assertTrue(WxTextSafetyStructure.pass(body, false));
    }
    @Test void aggregateAndEveryDetailMustAgreeWithPass() throws Exception {
        for (String decision : new String[]{"risky", "review", "reject", "unknown", "", "PASS"}) {
            for (int location = 0; location < 3; location++) {
                var body = valid();
                ObjectNode node = location == 0 ? (ObjectNode)body.path("result") : location == 1 ? model(body) : keyword(body);
                node.put("suggest", decision);
                assertFalse(WxTextSafetyStructure.pass(body, true), decision + " location=" + location);
            }
        }
    }
    @Test void emptyKeywordCannotContainRiskOrAdditionalFieldsEvenNull() throws Exception {
        for (String field : new String[]{"suggest", "label", "keyword", "level", "prob", "unknown"}) {
            var body = valid(); keyword(body).putNull(field);
            assertFalse(WxTextSafetyStructure.pass(body, true), field);
        }
        var body = valid(); keyword(body).put("label", 20006).put("suggest", "pass");
        assertFalse(WxTextSafetyStructure.pass(body, true));
    }
    @Test void modelCannotBeMissingUnknownOrEmpty() throws Exception {
        var body = valid(); body.withArray("detail").remove(0);
        assertFalse(WxTextSafetyStructure.pass(body, true));
        body = valid(); model(body).put("strategy", "unknown");
        assertFalse(WxTextSafetyStructure.pass(body, true));
        body = valid(); model(body).remove("suggest");
        assertFalse(WxTextSafetyStructure.pass(body, true));
        body = valid(); model(body).put("label", 20006);
        assertFalse(WxTextSafetyStructure.pass(body, true));
    }
    @Test void everyRequiredFieldAndCodeMustExistAndHaveExactType() throws Exception {
        for (String field : new String[]{"errcode", "errmsg", "trace_id", "result", "detail"}) {
            var body = valid(); body.remove(field);
            assertFalse(WxTextSafetyStructure.pass(body, true), field);
        }
        for (int location = 0; location < 3; location++) {
            for (String field : new String[]{"errcode"}) {
                var body = valid(); ObjectNode node = location == 0 ? body : location == 1 ? model(body) : keyword(body);
                node.put(field, 1); assertFalse(WxTextSafetyStructure.pass(body, true));
                node.put(field, "0"); assertFalse(WxTextSafetyStructure.pass(body, true));
                node.putNull(field); assertFalse(WxTextSafetyStructure.pass(body, true));
                node.remove(field); assertFalse(WxTextSafetyStructure.pass(body, true));
            }
        }
        var body = valid(); ((ObjectNode)body.path("result")).remove("suggest");
        assertFalse(WxTextSafetyStructure.pass(body, true));
    }
    @Test void malformedArraysAndMixedConflictsNeverPass() throws Exception {
        var body = valid(); body.putNull("detail"); assertFalse(WxTextSafetyStructure.pass(body, true));
        body = valid(); body.putArray("detail"); assertFalse(WxTextSafetyStructure.pass(body, true));
        body = valid(); body.withArray("detail").addNull(); assertFalse(WxTextSafetyStructure.pass(body, true));
        body = valid(); body.withArray("detail").addObject().put("strategy", "content_model").put("errcode", 0)
                .put("suggest", "review").put("label", 100);
        assertFalse(WxTextSafetyStructure.pass(body, true));
        body = valid(); body.put("verifiedEmptyKeywordPassStructure", true);
        assertFalse(WxTextSafetyStructure.pass(body, true));
    }
}
