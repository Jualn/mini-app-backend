package cn.jualn.miniapp.module.article.bo;

import com.fasterxml.jackson.databind.JsonNode;

public record ArticleSaveBO(String title, String summary, Long categoryId, Long publisherId,
        Long coverAttachmentId, JsonNode body, Boolean commentsEnabled, String publisherOrgId, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) java.util.List<Long> attachmentIds, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) JsonNode source, @com.fasterxml.jackson.annotation.JsonIgnore boolean sourcePresent) {
    public ArticleSaveBO(String title,String summary,Long categoryId,Long publisherId,Long coverAttachmentId,JsonNode body,Boolean commentsEnabled,String publisherOrgId) {this(title,summary,categoryId,publisherId,coverAttachmentId,body,commentsEnabled,publisherOrgId,null,null,false);}
    public ArticleSaveBO(String title,String summary,Long categoryId,Long publisherId,Long coverAttachmentId,JsonNode body,Boolean commentsEnabled) {this(title,summary,categoryId,publisherId,coverAttachmentId,body,commentsEnabled,null);}
    public ArticleSaveBO { if (body != null) body = body.deepCopy(); if (source != null) source = source.deepCopy(); if (attachmentIds != null) attachmentIds = java.util.List.copyOf(attachmentIds); }
    @Override public JsonNode source() { return source == null ? null : source.deepCopy(); }
    @Override public JsonNode body() { return body == null ? null : body.deepCopy(); }
}
