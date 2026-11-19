package cn.jualn.miniapp.module.article.service;
import cn.jualn.miniapp.module.article.entity.Article;
import com.fasterxml.jackson.databind.*;
import java.util.*;
public final class ArticleStoredFields {
    private ArticleStoredFields() {}
    public static JsonNode read(String value,ObjectMapper json) {
        if(value==null) return null;
        try {return json.readTree(value);} catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalStateException("Stored Article field is invalid",e);}
    }
    public static List<Long> files(Article article,ObjectMapper json) {return article.getAttachmentIds()==null?List.of():ArticleContentFields.files(read(article.getAttachmentIds(),json));}
    public static List<String> scenes(String value,ObjectMapper json) {return value==null?List.of():ArticleContentFields.scenes(read(value,json));}
    public static String encode(Object value,ObjectMapper json) {
        try {return value==null?null:json.writeValueAsString(value);} catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalStateException(e);}
    }
}
