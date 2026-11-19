package cn.jualn.miniapp.module.content.bo;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import cn.jualn.miniapp.module.post.bo.PostCategoryBO;
import cn.jualn.miniapp.module.post.bo.PostTopicBO;
public final class AdminPostProjection {
    private AdminPostProjection() {}
    public record Organization(String postType,PostCategoryBO category,List<PostTopicBO> topics) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Node(String availability,String id,String type,String contentPreview) {
        public static Node unavailable() {return new Node("UNAVAILABLE",null,null,null);}
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QaContext(Node question,Node answerRoot,Node replyTo,String evaluatedAt,String questionVersion) {public QaContext(Node q,Node a,Node r,String at){this(q,a,r,at,null);}}
    public record Relation(Long question,Long answerRoot,Long replyTo,String questionVersion) {public Relation(Long q,Long a,Long r){this(q,a,r,null);}}
}
