package cn.jualn.miniapp.module.points.dto;

import cn.jualn.miniapp.module.points.bo.PointsBO;
import cn.jualn.miniapp.module.points.service.PointsService;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static cn.jualn.miniapp.module.points.PointsProblems.invalid;

/** Strict local parsing: legacy global coercions and open-property behavior do not apply here. */
public final class PointsRequests {
    private PointsRequests() {}
    public record Create(String title,String description,List<String> fileIds,String acceptingOrganizationId) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING) public static Create parse(JsonNode root) {
            closed(root,Set.of("title","description","fileIds","acceptingOrganizationId"));
            var files=new ArrayList<String>();var items=root.get("fileIds");
            if(items==null || !items.isArray()) throw invalid("fileIds");
            for(var item:items) files.add(string(item,"fileIds",128));
            var body=new Create(string(root.get("title"),"title",120),string(root.get("description"),"description",10000),List.copyOf(files),string(root.get("acceptingOrganizationId"),"acceptingOrganizationId",128));
            PointsService.validate(body.command());return body;
        }
        public PointsBO.Create command() { return new PointsBO.Create(title,description,fileIds,acceptingOrganizationId); }
    }
    public record Close(String reason) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING) public static Close parse(JsonNode root) {closed(root,Set.of("reason"));return new Close(string(root.get("reason"),"reason",2000));}
    }
    public record Review(PointsBO.Status decision,Integer awardedPoints,String reviewNote) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING) public static Review parse(JsonNode root) {
            closed(root,Set.of("decision","awardedPoints","reviewNote"));
            PointsBO.Status decision;
            try { decision=PointsBO.Status.valueOf(string(root.get("decision"),"decision",16)); }
            catch(IllegalArgumentException invalidDecision) { throw invalid("decision"); }
            Integer points=root.has("awardedPoints")?integer(root.get("awardedPoints"),"awardedPoints",1,10):null;
            if(decision==PointsBO.Status.REJECTED && root.has("awardedPoints")) throw invalid("awardedPoints");
            var body=new Review(decision,points,root.has("reviewNote")?string(root.get("reviewNote"),"reviewNote",2000):null);
            PointsService.validate(body.command());return body;
        }
        public PointsBO.Review command() { return new PointsBO.Review(decision,awardedPoints,reviewNote); }
    }
    public record Reversal(String reason,String basis) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING) public static Reversal parse(JsonNode root) {
            closed(root,Set.of("reason","basis"));return new Reversal(string(root.get("reason"),"reason",2000),string(root.get("basis"),"basis",2000));
        }
    }
    public record Correction(int correctedPoints,String reason,String basis) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING) public static Correction parse(JsonNode root) {
            closed(root,Set.of("correctedPoints","reason","basis"));return new Correction(integer(root.get("correctedPoints"),"correctedPoints",1,10),string(root.get("reason"),"reason",2000),string(root.get("basis"),"basis",2000));
        }
        public PointsBO.Adjustment command() {return new PointsBO.Adjustment(correctedPoints,reason,basis);}
    }
    public record FileDeclaration(String fileName,String kind,String contentType,int sizeBytes) {}
    public record Upload(List<FileDeclaration> files) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING) public static Upload parse(JsonNode root) {
            closed(root,Set.of("files"));var items=root.get("files");
            if(items==null || !items.isArray() || items.isEmpty() || items.size()>9) throw invalid("files");
            var files=new ArrayList<FileDeclaration>();
            for(var item:items) {
                closed(item,Set.of("fileName","kind","contentType","sizeBytes"));
                var kind=string(item.get("kind"),"kind",16);var type=string(item.get("contentType"),"contentType",128);
                boolean valid=switch(kind) {
                    case "IMAGE" -> Set.of("image/jpeg","image/png","image/webp").contains(type);
                    case "VIDEO" -> "video/mp4".equals(type);
                    case "PDF" -> "application/pdf".equals(type);
                    case "WORD" -> Set.of("application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document").contains(type);
                    default -> false;
                };
                if(!valid) throw invalid("contentType");
                files.add(new FileDeclaration(string(item.get("fileName"),"fileName",255),kind,type,integer(item.get("sizeBytes"),"sizeBytes",1,10485760)));
            }
            return new Upload(List.copyOf(files));
        }
    }
    public record Register(String uploadId,int fileIndex) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING) public static Register parse(JsonNode root) {
            closed(root,Set.of("uploadId","fileIndex"));return new Register(string(root.get("uploadId"),"uploadId",128),integer(root.get("fileIndex"),"fileIndex",0,8));
        }
    }
    private static void closed(JsonNode root,Set<String> fields) {
        if(root==null || !root.isObject()) throw invalid("");
        root.fieldNames().forEachRemaining(name->{if(!fields.contains(name)) throw invalid(name);});
    }
    private static String string(JsonNode value,String field,int max) {
        if(value==null || !value.isTextual()) throw invalid(field);
        String text=value.textValue();PointsService.text(text,max,field);return text;
    }
    private static int integer(JsonNode value,String field,int min,int max) {
        if(value==null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue()<min || value.intValue()>max) throw invalid(field);
        return value.intValue();
    }
}
