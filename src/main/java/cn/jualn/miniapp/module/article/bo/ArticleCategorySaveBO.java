package cn.jualn.miniapp.module.article.bo;
public record ArticleCategorySaveBO(String name, Integer displayOrder, Boolean enabled, Long parentId, java.util.List<String> sceneCodes) {
 public ArticleCategorySaveBO(String name,Integer displayOrder,Boolean enabled,Long parentId) {this(name,displayOrder,enabled,parentId,null);}
 public ArticleCategorySaveBO(String name, Integer displayOrder, Boolean enabled) { this(name,displayOrder,enabled,null); }
}
