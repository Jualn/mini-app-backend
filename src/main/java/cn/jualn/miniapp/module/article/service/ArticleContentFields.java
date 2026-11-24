package cn.jualn.miniapp.module.article.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Validation for the independent fields; null list is omission, empty list is explicit clearing. */
public final class ArticleContentFields {
    private ArticleContentFields() {}
    public static final Set<String> SCENES = Set.of("PARTY_NEWS", "RED_BOOK_CORNER", "RED_CLOUD_CLASSROOM", "COMPETITION_RESOURCES", "E_SAFETY", "LEARNING_MATERIALS");
    public static List<String> scenes(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > 1) ArticleBodyCodec.invalid("#/sceneCodes", "Use at most one known scene code");
        var result = new ArrayList<String>();
        for (var item : node) {
            if (!item.isTextual() || !SCENES.contains(item.textValue())) ArticleBodyCodec.invalid("#/sceneCodes", "Use a known scene code");
            result.add(item.textValue());
        }
        return List.copyOf(result);
    }
    public static List<Long> files(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > 9) ArticleBodyCodec.invalid("#/attachmentIds", "Use at most nine unique file IDs");
        var result = new ArrayList<Long>();
        for (var item : node) {
            long id = ArticleBodyCodec.id(item, "#/attachmentIds");
            if (result.contains(id)) ArticleBodyCodec.invalid("#/attachmentIds", "Duplicate file ID");
            result.add(id);
        }
        return List.copyOf(result);
    }
    public static JsonNode source(JsonNode node) {
        if (node == null || node.isNull()) return null;
        ArticleBodyCodec.object(node,"#/source",Set.of("name","url"),Set.of("name"));
        ArticleBodyCodec.text(node.get("name"),1,120,"#/source/name",true);
        if (node.has("url")) ArticleBodyCodec.safeLink(ArticleBodyCodec.text(node.get("url"),1,2048,"#/source/url",true),"#/source/url");
        return node.deepCopy();
    }
}
