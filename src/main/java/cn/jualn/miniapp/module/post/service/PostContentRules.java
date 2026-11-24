package cn.jualn.miniapp.module.post.service;

import cn.jualn.miniapp.module.post.PostProblems;
import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.media.enums.MediaType;
import java.util.List;

public final class PostContentRules {
    private PostContentRules() { }
    public static void validate(String content,List<AttachmentItemBO> media) {
        if(content==null || content.length()>5000 || (content.isBlank() && (media==null || media.stream().noneMatch(m->m!=null && m.getType()==MediaType.IMAGE))))
            throw PostProblems.invalid("content","INVALID_VALUE","Provide text or at least one valid image");
    }
}
