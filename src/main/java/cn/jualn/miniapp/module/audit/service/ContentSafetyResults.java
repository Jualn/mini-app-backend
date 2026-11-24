package cn.jualn.miniapp.module.audit.service;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cn.jualn.miniapp.module.audit.bo.SafetyReviewProjection.Facts;
import java.util.*;
/** POST/COMMENT policy only. Other domains retain their own definitive-pass policy. */
public final class ContentSafetyResults {
    private ContentSafetyResults() {}
    public record Observation(String execution,String result,Facts facts) {}
    public static Observation interpret(ObjectMapper json,String raw) {
        try {
            JsonNode source=json.readTree(raw);
            if(source==null || !source.isObject()) return new Observation("unknown","unknown",new Facts(null,null,List.of(),null));
            ObjectNode safe=json.createObjectNode();
            // Deliberately exclude callback identities, URLs, credentials and execution parameters.
            for(String name:List.of("errcode","errCode","result","detail","traceId","trace_id"))
                if(source.has(name)) safe.set(name,redact(json,source.get(name)));
            JsonNode code=source.has("errcode")?source.get("errcode"):source.get("errCode");
            String suggestion=source.path("result").path("suggest").isTextual()?source.path("result").path("suggest").asText():null;
            boolean known=code!=null && code.isIntegralNumber() && code.bigIntegerValue().signum()==0 && Set.of("pass","risky","review","reject").contains(suggestion==null?"":suggestion);
            JsonNode verifiedPass = source.path("_verifiedPass");
            boolean verifiedTextPass = "pass".equals(suggestion) && verifiedPass.isBoolean() && verifiedPass.booleanValue();
            if ("pass".equals(suggestion) && source.has("_verifiedPass") && !verifiedTextPass) known=false;
            JsonNode details=source.get("detail");
            // For a verified text pass, the raw-response validator already checked every detail.
            if(!verifiedTextPass && details!=null && !details.isNull()) {
                if(!details.isArray()) known=false;
                else for(JsonNode d:details) {
                    JsonNode ec=d.has("errcode")?d.get("errcode"):d.get("errCode");
                    String ds=d.path("suggest").asText("");
                    if(ec==null || !ec.isIntegralNumber() || ec.bigIntegerValue().signum()!=0 || !Set.of("pass","risky","review","reject").contains(ds)
                        || "pass".equals(suggestion) && !"pass".equals(ds)) known=false;
                }
            }
            var labels=new TreeSet<String>();collectLabels(safe,labels);
            var facts=new Facts(code==null || code.isNull()?null:code.asText(),suggestion,List.copyOf(labels),json.writeValueAsString(safe));
            // Preserve the existing POST/COMMENT policy: Provider risk suggestions require human review.
            // A non-overridable normalized reject needs an explicitly approved domain rule, not a name match.
            return new Observation(known?"succeeded":"unknown",known?("pass".equals(suggestion)?"pass":"review"):"unknown",facts);
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) {
            return new Observation("unknown","unknown",new Facts(null,null,List.of(),null));
        }
    }
    private static JsonNode redact(ObjectMapper json,JsonNode input) {
        if(input.isObject()) {
            var result=json.createObjectNode();input.fields().forEachRemaining(e->{
                if(Set.of("errcode","errCode","strategy","suggest","label","prob","result","detail","traceId","trace_id").contains(e.getKey())) result.set(e.getKey(),redact(json,e.getValue()));
            });return result;
        }
        if(input.isArray()) {var result=json.createArrayNode();input.forEach(e->result.add(redact(json,e)));return result;}
        return input;
    }
    private static void collectLabels(JsonNode n,Set<String> labels) {
        if(n.isObject()) n.fields().forEachRemaining(e->{if("label".equals(e.getKey()) && e.getValue().isValueNode() && !e.getValue().isNull()) labels.add(e.getValue().asText());else collectLabels(e.getValue(),labels);});
        else if(n.isArray()) n.forEach(v->collectLabels(v,labels));
    }
}
