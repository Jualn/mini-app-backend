package cn.jualn.miniapp.module.learningtask.dto.admin;
import cn.jualn.miniapp.module.media.bo.PointMaterialBO.Declaration;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.*;
import java.io.IOException;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.invalid;
public final class LearningTaskFileRequest {
    private LearningTaskFileRequest() {}
    @JsonDeserialize(using=UploadJson.class) public record Upload(List<Declaration> files) {}
    @JsonDeserialize(using=RegisterJson.class) public record Register(String uploadId,int fileIndex) {}
    public static class UploadJson extends JsonDeserializer<Upload> {
        @Override public Upload deserialize(JsonParser parser,DeserializationContext context) throws IOException {
            JsonNode root=parser.getCodec().readTree(parser);closed(root,Set.of("files"));var nodes=root.path("files");
            if(!nodes.isArray() || nodes.isEmpty() || nodes.size()>9) throw invalid("files");
            List<Declaration> declarations=new ArrayList<>();
            for(var node:nodes) {
                closed(node,Set.of("fileName","kind","contentType","sizeBytes"));
                if(!node.path("sizeBytes").isIntegralNumber() || !node.path("sizeBytes").canConvertToInt()) throw invalid("sizeBytes");
                declarations.add(new Declaration(text(node,"fileName",255),text(node,"kind",16),text(node,"contentType",128),node.path("sizeBytes").intValue()));
            }
            return new Upload(List.copyOf(declarations));
        }
    }
    public static class RegisterJson extends JsonDeserializer<Register> {
        @Override public Register deserialize(JsonParser parser,DeserializationContext context) throws IOException {
            JsonNode root=parser.getCodec().readTree(parser);closed(root,Set.of("uploadId","fileIndex"));var index=root.path("fileIndex");
            if(!index.isIntegralNumber() || !index.canConvertToInt() || index.intValue()<0 || index.intValue()>8) throw invalid("fileIndex");
            return new Register(text(root,"uploadId",128),index.intValue());
        }
    }
    private static String text(JsonNode node,String field,int limit) {
        var value=node.path(field);if(!value.isTextual() || value.textValue().isBlank() || value.textValue().codePointCount(0,value.textValue().length())>limit) throw invalid(field);return value.textValue();
    }
    private static void closed(JsonNode root,Set<String> allowed) {if(!root.isObject()) throw invalid("body");root.fieldNames().forEachRemaining(f->{if(!allowed.contains(f)) throw invalid(f);});}
}
