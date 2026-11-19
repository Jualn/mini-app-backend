package cn.jualn.miniapp.module.article.bo;
import java.time.LocalDateTime;
public record ArticleCategoryBO(Long id, Long parentId, String name, int displayOrder, boolean enabled, Long revision,
 LocalDateTime createdAt, LocalDateTime updatedAt, java.util.List<String> sceneCodes) {
 public ArticleCategoryBO(Long id,Long parentId,String name,int displayOrder,boolean enabled,Long revision,LocalDateTime createdAt,LocalDateTime updatedAt) {this(id,parentId,name,displayOrder,enabled,revision,createdAt,updatedAt,java.util.List.of());}
}
