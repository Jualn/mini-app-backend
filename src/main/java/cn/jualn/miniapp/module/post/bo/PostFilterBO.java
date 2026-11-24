package cn.jualn.miniapp.module.post.bo;

import java.time.LocalDateTime;

public record PostFilterBO(String postType, Long categoryId, Long topicId, Long userId, String keyword,
                           Integer status, Long lastId, LocalDateTime pendingAfter, int pageSize,String answerState) {
    public PostFilterBO(String type,Long category,Long topic,Long user,String keyword,Integer status,Long last,LocalDateTime pending,int size) {this(type,category,topic,user,keyword,status,last,pending,size,null); }
}
