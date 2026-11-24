package cn.jualn.miniapp.module.article.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Component;

/** Validates the closed V1 vocabulary without rewriting or dropping source content. */
@Component
public class ArticleBodyCodec {
    public record Document(JsonNode body, String plainText, String safetyText, Map<Long,String> references, boolean hasContent) {}

    public Document validate(JsonNode source, Long coverId) {
        JsonNode body = source == null ? null : source.deepCopy();
        object(body, "#/body", Set.of("format","version","nodes"), Set.of("format","version","nodes"));
        if (!"ARTICLE_RICH_TEXT".equals(body.path("format").asText()) || !body.path("version").isIntegralNumber()
                || body.path("version").intValue()!=1) invalid("#/body", "Unsupported body format or version");
        array(body.get("nodes"),0,500,"#/body/nodes");
        List<String> lines=new ArrayList<>();
        List<String> safety=new ArrayList<>();
        Map<Long,String> references=new LinkedHashMap<>();
        if(coverId!=null) reference(references,coverId,"IMAGE","#/coverAttachmentId");
        int images=0, videos=0, textLength=0;
        boolean hasContent=false;
        for(int i=0;i<body.get("nodes").size();i++) {
            JsonNode node=body.get("nodes").get(i);
            String p="#/body/nodes/"+i;
            String type=node.path("type").asText();
            Set<String> allowed; Set<String> required;
            switch(type) {
                case "PARAGRAPH" -> { allowed=Set.of("type","runs","align"); required=Set.of("type","runs"); }
                case "HEADING" -> { allowed=Set.of("type","level","runs","align"); required=Set.of("type","level","runs"); }
                case "QUOTE" -> { allowed=Set.of("type","runs"); required=allowed; }
                case "LIST" -> { allowed=Set.of("type","ordered","items"); required=allowed; }
                case "IMAGE" -> { allowed=Set.of("type","attachmentId","alt","caption"); required=Set.of("type","attachmentId","alt"); }
                case "VIDEO" -> { allowed=Set.of("type","attachmentId","posterAttachmentId","caption"); required=Set.of("type","attachmentId"); }
                case "DIVIDER" -> { allowed=Set.of("type"); required=allowed; }
                default -> throw invalidProblem(p,"Unknown body node");
            }
            object(node,p,allowed,required);
            if(node.has("align") && !Set.of("LEFT","CENTER","RIGHT").contains(node.get("align").asText())) invalid(p+"/align","Invalid alignment");
            if("HEADING".equals(type) && (!node.get("level").isIntegralNumber() || node.get("level").intValue()<1 || node.get("level").intValue()>3)) invalid(p+"/level","Invalid heading level");
            if(node.has("runs")) {
                String line=runs(node.get("runs"),p+"/runs",safety);
                lines.add(line); textLength+=length(line); hasContent |= !blank(line);
            }
            if("LIST".equals(type)) {
                if(!node.get("ordered").isBoolean()) invalid(p+"/ordered","Boolean required");
                array(node.get("items"),1,100,p+"/items");
                for(int j=0;j<node.get("items").size();j++) {
                    var item=node.get("items").get(j); String ip=p+"/items/"+j;
                    object(item,ip,Set.of("runs"),Set.of("runs"));
                    String line=runs(item.get("runs"),ip+"/runs",safety);
                    lines.add(line); textLength+=length(line); hasContent |= !blank(line);
                }
            }
            if("IMAGE".equals(type) || "VIDEO".equals(type)) {
                reference(references,id(node.get("attachmentId"),p+"/attachmentId"),type,p);
                hasContent=true;
                if("IMAGE".equals(type)) { images++; String alt=text(node.get("alt"),0,300,p+"/alt",false); safety.add(alt); textLength+=length(alt); }
                else { videos++; if(node.has("posterAttachmentId")) reference(references,id(node.get("posterAttachmentId"),p+"/posterAttachmentId"),"IMAGE",p); }
                if(node.has("caption")) {
                    String caption=text(node.get("caption"),0,500,p+"/caption",false);
                    safety.add(caption); textLength+=length(caption);
                    if(!caption.isEmpty()) lines.add(caption);
                }
            }
            if(textLength>50000) invalid("#/body","Body exceeds text limits");
        }
        if(images>20 || videos>5 || references.size()>30 || textLength>50000) invalid("#/body","Body exceeds text or media limits");
        return new Document(body,String.join("\n",lines),String.join("\n",safety),Collections.unmodifiableMap(references),hasContent);
    }
    private String runs(JsonNode runs,String p,List<String> safety) {
        array(runs,0,200,p); StringBuilder line=new StringBuilder();
        for(int i=0;i<runs.size();i++) {
            var run=runs.get(i); String rp=p+"/"+i;
            object(run,rp,Set.of("text","marks","href"),Set.of("text"));
            String value=text(run.get("text"),1,5000,rp+"/text",true); line.append(value); safety.add(value);
            if(line.codePointCount(0,line.length())>50000) invalid(p,"Body exceeds text limits");
            if(run.has("marks")) {
                array(run.get("marks"),0,5,rp+"/marks"); Set<String> marks=new HashSet<>();
                for(var mark:run.get("marks")) if(!mark.isTextual() || !Set.of("BOLD","ITALIC","UNDERLINE","STRIKE","CODE").contains(mark.asText()) || !marks.add(mark.asText())) invalid(rp+"/marks","Invalid or duplicate mark");
            }
            if(run.has("href")) { String href=text(run.get("href"),1,2048,rp+"/href",true); safeLink(href,rp+"/href"); safety.add(href); }
        }
        return line.toString();
    }
    public static void safeLink(String value,String pointer) {
        try {
            URI uri=URI.create(value); String host=uri.getHost();
            if(host!=null) host=host.toLowerCase(Locale.ROOT);
            // No DNS resolution/remote fetch: links are display-only. Reject literal/private and local names.
            if(!value.startsWith("https://") || host==null || uri.getUserInfo()!=null || (uri.getPort()!=-1 && uri.getPort()!=443)
                    || !host.matches("(?i)[a-z0-9][a-z0-9.-]*\\.[a-z]{2,63}")
                    || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".localhost")
                    || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        } catch(IllegalArgumentException e) { invalid(pointer,"Unsafe HTTPS link"); }
    }
    public static String text(JsonNode value,int min,int max,String p,boolean nonblank) {
        if(value==null || !value.isTextual() || length(value.asText())<min || length(value.asText())>max || (nonblank && blank(value.asText()))) invalid(p,"Invalid text length or value");
        if(value.asText().codePoints().anyMatch(c->c>=0xd800 && c<=0xdfff)) invalid(p,"Invalid Unicode text");
        return value.asText();
    }
    public static int length(String value) { return value.codePointCount(0,value.length()); }
    /** Matches JSON Schema's ECMAScript whitespace, including NBSP and BOM. */
    public static boolean blank(String value) { return value.codePoints().allMatch(ArticleBodyCodec::whitespace); }
    public static String trim(String value) {
        int start=0,end=value.length();
        while(start<end && whitespace(value.codePointAt(start))) start+=Character.charCount(value.codePointAt(start));
        while(end>start && whitespace(value.codePointBefore(end))) end-=Character.charCount(value.codePointBefore(end));
        return value.substring(start,end);
    }
    private static boolean whitespace(int c) {
        return (c>=9 && c<=13) || c==0x20 || c==0xa0 || c==0x1680 || (c>=0x2000 && c<=0x200a)
                || c==0x2028 || c==0x2029 || c==0x202f || c==0x205f || c==0x3000 || c==0xfeff;
    }
    private void reference(Map<Long,String> refs,long id,String type,String p) {
        String old=refs.putIfAbsent(id,type); if(old!=null && !old.equals(type)) invalid(p,"Attachment used with incompatible kinds");
    }
    public static long id(JsonNode value,String p) {
        if(value==null || !value.isTextual() || length(value.asText())<1 || length(value.asText())>128) invalid(p,"Invalid resource ID");
        try { if(!value.asText().matches("[1-9][0-9]*")) throw new IllegalArgumentException();
            return Long.parseLong(value.asText());
        } catch(IllegalArgumentException e) { throw ContractProblemException.validation(new ContractProblemException.Violation("body",p,"INVALID_REFERENCE","Resource reference is unavailable")); }
    }
    public static void object(JsonNode value,String p,Set<String> allowed,Set<String> required) {
        if(value==null || !value.isObject()) invalid(p,"Object required");
        value.fieldNames().forEachRemaining(name->{if(!allowed.contains(name)) invalid(p+"/"+name,"Unknown property");});
        for(String name:required) if(!value.has(name)) invalid(p+"/"+name,"Required property");
    }
    private void array(JsonNode value,int min,int max,String p) {
        if(value==null || !value.isArray() || value.size()<min || value.size()>max) invalid(p,"Invalid array size");
    }
    public static void invalid(String p,String detail) { throw invalidProblem(p,detail); }
    public static void invalidQuery(String p,String detail) {
        throw ContractProblemException.validation(new ContractProblemException.Violation("query",p,"INVALID_VALUE",detail));
    }
    private static ContractProblemException invalidProblem(String p,String detail) {
        return ContractProblemException.validation(new ContractProblemException.Violation("body",p,"INVALID_VALUE",detail));
    }
}
