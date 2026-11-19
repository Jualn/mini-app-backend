package cn.jualn.miniapp.module.learningtask.dto.admin;

import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Decision;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.invalid;

public final class LearningTaskSubmissionRequest {
    private LearningTaskSubmissionRequest() {}
    @JsonDeserialize(using=MaterialJson.class)
    public record Material(String text,List<String> fileIds,Boolean confirmed) {}
    @JsonDeserialize(using=ReviewJson.class)
    public record Review(Decision decision,String reason) {}
    public static class MaterialJson extends JsonDeserializer<Material> {
        public Material deserialize(JsonParser p,DeserializationContext context) throws IOException {
            JsonNode root=p.getCodec().readTree(p); closed(root,Set.of("text","fileIds","confirmed"));
            String text=root.has("text")?string(root.get("text"),"text",10000):null;
            List<String> files=new java.util.ArrayList<>();
            if(root.has("fileIds")) {
                JsonNode items=root.get("fileIds");
                if(!items.isArray() || items.isEmpty() || items.size()>9) throw invalid("fileIds");
                for(JsonNode item:items) files.add(string(item,"fileIds",128));
                if(new java.util.HashSet<>(files).size()!=files.size()) throw invalid("fileIds");
            }
            if(text==null && files.isEmpty()) throw invalid("text");
            Boolean confirmed=null;
            if(root.has("confirmed")) {
                if(!root.get("confirmed").isBoolean() || !root.get("confirmed").booleanValue()) throw invalid("confirmed");
                confirmed=true;
            }
            return new Material(text,List.copyOf(files),confirmed);
        }
    }
    public static class ReviewJson extends JsonDeserializer<Review> {
        public Review deserialize(JsonParser p,DeserializationContext context) throws IOException {
            JsonNode root=p.getCodec().readTree(p); closed(root,Set.of("decision","reason"));
            Decision decision;
            try { decision=Decision.valueOf(string(root.path("decision"),"decision",16)); }
            catch(IllegalArgumentException failure) { throw invalid("decision"); }
            String reason=root.has("reason")?string(root.get("reason"),"reason",1000):null;
            if(decision==Decision.REJECT && reason==null) throw invalid("reason");
            return new Review(decision,reason);
        }
    }
    private static String string(JsonNode node,String field,int max) {
        if(!node.isTextual()) throw invalid(field);
        String value=node.textValue();
        if(value.codePointCount(0,value.length())>max || value.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||c==0xFEFF)) throw invalid(field);
        return value;
    }
    private static void closed(JsonNode root,Set<String> fields) {
        if(!root.isObject()) throw invalid("body");
        root.fieldNames().forEachRemaining(name->{if(!fields.contains(name)) throw invalid(name);});
    }
}
