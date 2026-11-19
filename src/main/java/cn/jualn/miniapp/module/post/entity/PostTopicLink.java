package cn.jualn.miniapp.module.post.entity;

import lombok.Data;

@Data
public class PostTopicLink {
    private Long postId;
    private Long topicId;
    private Integer position;
}
