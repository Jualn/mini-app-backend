package cn.jualn.miniapp.module.content.service;

import cn.jualn.miniapp.module.content.bo.AdminPostProjection.*;
import cn.jualn.miniapp.module.content.mapper.AdminContentMapper;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.time.ZoneId;

@Service @RequiredArgsConstructor
public class AdminQaContextReader {
    private final AdminContentMapper mapper;
    private final CurrentAuthorization authorization;
    public QaContext management(String type,long id) {
        // The owner has already authorized the target; authorize the related POST namespace independently.
        Relation relation="post".equals(type)?mapper.postRelation(id):mapper.commentRelation(id);
        if(relation==null) return null;
        var d=authorization.current(AdminStpUtil.STP_LOGIC.getLoginIdAsLong());
        boolean allowed=d.source()==cn.jualn.miniapp.module.authorization.model.RbacModels.Source.LEGACY
            ?d.accountEligible() && (d.legacyPermissions().contains("*") || d.legacyPermissions().contains("content:read"))
            :d.allows("content:read","POST");
        return project(relation,allowed);
    }
    /** Report owner has authorized the case target; only return its fixed question/answer context. */
    public QaContext reportEvidence(String type,long id) {
        Relation relation="post".equals(type)?mapper.postRelation(id):mapper.commentRelation(id);
        return relation==null?null:project(relation,true);
    }
    /** Called by the safety owner only after same-source POST_SAFETY_REVIEW authorization. */
    public QaContext safety(Relation fixed) {return fixed==null || fixed.question()==null?null:project(fixed,true);}
    private QaContext project(Relation r,boolean allowed) {
        String preview=allowed?mapper.questionPreview(r.question()):null;
        Node question=node(r.question(),"post",preview);
        boolean visible=preview!=null;
        Node root=r.answerRoot()==null?null:node(r.answerRoot(),"comment",visible?mapper.answerPreview(r.answerRoot(),r.question(),r.answerRoot()):null);
        Node reply=r.replyTo()==null?null:node(r.replyTo(),"comment",visible && root!=null && "AVAILABLE".equals(root.availability())?mapper.answerPreview(r.replyTo(),r.question(),r.answerRoot()):null);
        return new QaContext(question,root,reply,OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString(),r.questionVersion());
    }
    private Node node(Long id,String type,String preview) {return preview==null?Node.unavailable():new Node("AVAILABLE",id.toString(),type,preview);}
}
