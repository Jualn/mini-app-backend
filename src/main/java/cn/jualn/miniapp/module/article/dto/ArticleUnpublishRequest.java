package cn.jualn.miniapp.module.article.dto;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.Set;
import cn.jualn.miniapp.module.article.service.ArticleBodyCodec;
@JsonDeserialize(using=ArticleUnpublishRequest.Json.class)
public record ArticleUnpublishRequest(String mode,String reason) {
    public static class Json extends JsonDeserializer<ArticleUnpublishRequest> {
        @Override public ArticleUnpublishRequest getNullValue(DeserializationContext context) {
            ArticleBodyCodec.invalid("#","Object required");return null;
        }
        @Override public ArticleUnpublishRequest deserialize(JsonParser parser,DeserializationContext context) throws IOException {
            JsonNode root=parser.getCodec().readTree(parser);if(!root.isObject()) ArticleBodyCodec.invalid("#","Object required");
            root.fieldNames().forEachRemaining(f->{if(!Set.of("mode","reason").contains(f)) ArticleBodyCodec.invalid("#/"+f,"Unknown field");});
            String mode="NORMAL",reason=null;
            if(root.has("mode")) {if(!root.path("mode").isTextual() || !Set.of("NORMAL","SAFETY").contains(root.path("mode").textValue())) ArticleBodyCodec.invalid("#/mode","Use NORMAL or SAFETY");mode=root.path("mode").textValue();}
            if(root.has("reason")) {var n=root.path("reason");if(!n.isTextual() || n.textValue().isBlank() || n.textValue().codePointCount(0,n.textValue().length())>1000) ArticleBodyCodec.invalid("#/reason","Nonblank reason required");reason=n.textValue();}
            if("SAFETY".equals(mode) && reason==null) ArticleBodyCodec.invalid("#/reason","Safety reason required");
            return new ArticleUnpublishRequest(mode,reason);
        }
    }
}
