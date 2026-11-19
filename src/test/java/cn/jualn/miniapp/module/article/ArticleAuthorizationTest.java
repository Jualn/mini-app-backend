package cn.jualn.miniapp.module.article;

import cn.dev33.satoken.SaManager;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.authorization.service.RbacAuthorizationReader;
import cn.jualn.miniapp.module.authorization.model.RbacModels.*;
import cn.jualn.miniapp.module.article.service.ArticleAuthorization;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import static cn.jualn.miniapp.module.article.service.ArticleAuthorization.Capability.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArticleAuthorizationTest {
 private final RbacAuthorizationReader reader=mock(RbacAuthorizationReader.class);
 private final ArticleAuthorization authorization=new ArticleAuthorization(reader);
 @BeforeEach void session() {
  var storage=mock(cn.dev33.satoken.context.model.SaStorage.class); Map<String,Object> values=new HashMap<>();
  when(storage.get(anyString())).thenAnswer(i->values.get(i.getArgument(0)));
  when(storage.set(anyString(),any())).thenAnswer(i->{values.put(i.getArgument(0),i.getArgument(1));return storage;});
  when(storage.delete(anyString())).thenAnswer(i->{values.remove(i.getArgument(0));return storage;});
  SaManager.getSaTokenContext().setContext(mock(cn.dev33.satoken.context.model.SaRequest.class),mock(cn.dev33.satoken.context.model.SaResponse.class),storage);
  AdminStpUtil.STP_LOGIC.switchTo(9L);
 }
 @AfterEach void clearSession() { SaManager.getSaTokenContext().clearContext(); }
 @Test void canonicalCapabilitiesRequireTheirExactArticlePermission() {
  var mappings=Map.ofEntries(Map.entry(READ,"article:read"),Map.entry(CREATE,"article:edit"),Map.entry(EDIT,"article:edit"),
    Map.entry(PUBLISH,"article:publish"),Map.entry(UNPUBLISH,"article:unpublish"),Map.entry(CATEGORY_READ,"article-category:read"),
    Map.entry(CATEGORY_MANAGE,"article-category:manage"),Map.entry(PUBLISHER_READ,"article-publisher:read"),
    Map.entry(PUBLISHER_MANAGE,"article-publisher:manage"),Map.entry(MEDIA_READ,"media:read"),Map.entry(MEDIA,"media:write"));
  for(var entry:mappings.entrySet()) {
   when(reader.read(9)).thenReturn(decision(Source.ASSIGNMENT,true,List.of(),List.of(grant("ARTICLE",entry.getValue()))));
   assertEquals(9,authorization.require(entry.getKey()));
   for(var other:mappings.entrySet()) if(!other.getValue().equals(entry.getValue())) denied(other.getKey());
  }
 }
 @Test void legacyWildcardRootOtherNamespaceAndIneligibleAccountsNeverAuthorizeArticle() {
  for(var d:List.of(decision(Source.LEGACY,true,List.of("*"),List.of()),
    decision(Source.ASSIGNMENT,true,List.of(),List.of(grant("AUTHORIZATION","authorization:root-manage"))),
    decision(Source.ASSIGNMENT,true,List.of(),List.of(grant("OFFICIAL_CONTENT","article:read","media:write"))),
    decision(Source.ASSIGNMENT,false,List.of(),List.of(grant("ARTICLE","article:read","media:write"))),
    decision(Source.ASSIGNMENT,true,List.of(),List.of()))) {
   when(reader.read(9)).thenReturn(d);
   for(var capability:ArticleAuthorization.Capability.values()) denied(capability);
  }
 }
 @Test void revocationIsObservedOnTheNextCallAndDependencyFailuresKeepTheirStatus() {
  when(reader.read(9)).thenReturn(decision(Source.ASSIGNMENT,true,List.of(),List.of(grant("ARTICLE","article:read"))),
    decision(Source.ASSIGNMENT,true,List.of(),List.of()));
  assertEquals(9,authorization.require(READ)); denied(READ);
  var unavailable=new ContractProblemException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"/problems/authorization-source-unavailable","Unavailable");
  when(reader.read(9)).thenThrow(unavailable);
  assertSame(unavailable,assertThrows(ContractProblemException.class,()->authorization.require(READ)));
 }
 private void denied(ArticleAuthorization.Capability capability) {
  assertEquals(403,assertThrows(ContractProblemException.class,()->authorization.require(capability)).getStatus().value());
 }
 private Grant grant(String namespace,String... permissions) { return new Grant("a","preset",namespace,"GLOBAL",List.of(permissions),null); }
 private Decision decision(Source source,boolean eligible,List<String> legacy,List<Grant> grants) {
  return new Decision(9,source,"v","c",LocalDateTime.now(),eligible,legacy,grants);
 }
}
