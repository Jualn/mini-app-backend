package cn.jualn.miniapp.module.article.service;
import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.module.article.mapper.*;
import cn.jualn.miniapp.module.article.bo.ArticleBO;
import cn.jualn.miniapp.module.user.service.UserCurrentLoginAccess;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service @RequiredArgsConstructor
public class ArticleFavorites {
 private final ArticleFavoriteMapper favorites;
 private final ArticleMapper articles;
 private final ArticleService service;
 private final UserCurrentLoginAccess users;
 public record State(String articleId,boolean favorited) {}
 public record Item(long articleId,LocalDateTime favoritedAt,ArticleBO article) {}
 public record Page(List<Item> items,String nextCursor) {}
 private long actor() {StpUtil.checkLogin();long id=StpUtil.getLoginIdAsLong();users.require(id);return id;}
 @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
 public State set(long id) {
  long user=actor();var row=ArticleWriter.require(articles.lock(id));
  if(row.getDeletedAt()!=null || !"PUBLISHED".equals(row.getPublishStatus())) ArticleWriter.require(null);
  service.assertPublic(id);
  favorites.add(user,id,ArticleWriter.now());return new State(Long.toString(id),true);
 }
 @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
 public State remove(long id) {long user=actor();favorites.remove(user,id);return new State(Long.toString(id),false);}
 @Transactional(isolation=Isolation.REPEATABLE_READ)
 public State get(long id) {long user=actor();boolean exists=favorites.exists(user,id);if(!exists) service.assertPublic(id);return new State(Long.toString(id),exists);}
 @Transactional(isolation=Isolation.REPEATABLE_READ)
 public Page list(String cursor,int size) {
  long user=actor();if(size<1 || size>50) ArticleBodyCodec.invalidQuery("#/pageSize","Use 1..50");
  String context="favorites/"+user;
  String position=ArticleCursor.decode(cursor,context);LocalDateTime at=null;Long id=null;
  if(position!=null) try {var parts=position.split("\\|",-1);if(parts.length!=2) throw new IllegalArgumentException();at=LocalDateTime.parse(parts[0]);id=Long.valueOf(parts[1]);if(id<=0) throw new IllegalArgumentException();}catch(IllegalArgumentException e) {throw ArticleCursor.invalid();}
  var rows=favorites.page(user,at,id,size+1);boolean more=rows.size()>size;var page=more?rows.subList(0,size):rows;
  var visible=service.publicSummaries(page.stream().map(r->r.articleId()).toList());
  var items=new ArrayList<Item>();
  for(var row:page) items.add(new Item(row.articleId(),row.favoritedAt(),visible.get(row.articleId())));
  var last=page.isEmpty()?null:page.get(page.size()-1);
  return new Page(List.copyOf(items),more?ArticleCursor.encode(last.favoritedAt()+"|"+last.articleId(),context):null);
 }
}
