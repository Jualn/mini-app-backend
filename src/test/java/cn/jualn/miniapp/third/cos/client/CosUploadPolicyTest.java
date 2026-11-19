package cn.jualn.miniapp.third.cos.client;
import cn.jualn.miniapp.third.cos.config.CosProperties;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import com.qcloud.cos.COSClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CosUploadPolicyTest {
 COSClient sdk=mock(COSClient.class);
 CosClient client=new CosClient(sdk,new CosProperties("unused","unused","campus-123","ap-test","https://cdn.example",600,"cdn.example"));
 @Test void everyObjectCreatingActionRequiresHeaderAndResourcesAreExact() throws Exception {
  var policy=new ObjectMapper().readTree(client.uploadPolicy(List.of("article/9/a.mp4","post/9/b.png")));
  var actions=new HashSet<String>();
  for(var statement:policy.path("statement")) {
   for(var resource:statement.path("resource")) {assertFalse(resource.asText().contains("*"));assertTrue(resource.asText().endsWith("/article/9/a.mp4") || resource.asText().endsWith("/post/9/b.png"));}
   if("allow".equals(statement.path("effect").asText())) for(var action:statement.path("action")) {
    actions.add(action.asText());
    if(Set.of("name/cos:PutObject","name/cos:InitiateMultipartUpload","name/cos:CompleteMultipartUpload").contains(action.asText()))
     assertEquals("true",statement.path("condition").path("string_equal").path("cos:x-cos-forbid-overwrite").asText());
   }
  }
  assertEquals(Set.of("name/cos:PutObject","name/cos:InitiateMultipartUpload","name/cos:CompleteMultipartUpload","name/cos:ListParts","name/cos:UploadPart","name/cos:AbortMultipartUpload"),actions);
  assertThrows(IllegalArgumentException.class,()->client.uploadPolicy(List.of("post/9/*")));
  assertThrows(IllegalArgumentException.class,()->client.getCredential("post/9/"));
 }
 @Test void missingOrVersionedBucketEvidenceRefusesCredentials() {
  assertEquals(503,assertThrows(ContractProblemException.class,()->client.getExactCredential(List.of("post/9/a"))).getStatus().value());
  when(sdk.getBucketVersioningConfiguration("campus-123")).thenReturn(new com.qcloud.cos.model.BucketVersioningConfiguration("Enabled"));
  assertEquals(503,assertThrows(ContractProblemException.class,()->client.getExactCredential(List.of("post/9/a"))).getStatus().value());
 }
 @Test void oversizedProviderStreamIsValidationFailureAndNeverAccepted(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
  var object=new com.qcloud.cos.model.COSObject();object.setObjectContent(new java.io.ByteArrayInputStream(new byte[]{1,2,3}));
  when(sdk.getObject("campus-123","source")).thenReturn(object);
  assertEquals(400,assertThrows(ContractProblemException.class,()->client.downloadObject("source",dir.resolve("file"),2)).getStatus().value());
 }
}
