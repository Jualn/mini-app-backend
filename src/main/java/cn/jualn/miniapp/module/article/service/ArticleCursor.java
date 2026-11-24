package cn.jualn.miniapp.module.article.service;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
/** Cursor is an opaque continuation bound to the complete query context. No authority is carried by it. */
public final class ArticleCursor {
 private ArticleCursor() {}
 public static String fingerprint(Object value) {
  try {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(value)));}
  catch(java.security.NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalStateException(e);}
 }
 public static String encode(String position, String context) {
  return Base64.getUrlEncoder().withoutPadding().encodeToString(("article-v1\n"+context+"\n"+position).getBytes(StandardCharsets.UTF_8));
 }
 public static String decode(String cursor, String context) {
  if(cursor==null) return null;
  try {
   if(cursor.length()>1024 || cursor.isEmpty()) throw new IllegalArgumentException();
   String text=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8);
   String prefix="article-v1\n"+context+"\n";
   if(!text.startsWith(prefix) || text.length()==prefix.length()) throw new IllegalArgumentException();
   String position=text.substring(prefix.length());
   if(!encode(position,context).equals(cursor)) throw new IllegalArgumentException();
   return position;
  } catch(IllegalArgumentException e) { throw invalid(); }
 }
 public static ContractProblemException invalid() {
  return new ContractProblemException(HttpStatus.BAD_REQUEST,"/problems/invalid-cursor","Cursor does not match this query");
 }
}
