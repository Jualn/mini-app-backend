package cn.jualn.miniapp.module.media.service;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.third.cos.service.CosService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ManagedArticleMediaInspectorTest {
 @org.junit.jupiter.api.Test void learningVideoRequiresFullProbeEvenWhenPublicVideoAllowsHeaderOnly() throws Exception {
  var inspector=new ManagedArticleMediaInspector(org.mockito.Mockito.mock(cn.jualn.miniapp.third.cos.service.CosService.class),new com.fasterxml.jackson.databind.ObjectMapper());
  var file=java.nio.file.Files.createTempFile("learning-video-header-",".mp4");
  try {
   java.nio.file.Files.write(file,new byte[]{0,0,0,24,'f','t','y','p','i','s','o','m'});
   assertFalse(inspector.verifiedVideoAvailable());
   assertEquals(503,assertThrows(ContractProblemException.class,()->inspector.verifiedVideo(file,12)).getStatus().value());
   assertEquals("video/mp4",inspector.video(file,12).contentType());
   org.springframework.test.util.ReflectionTestUtils.setField(inspector,"ffprobePath",file.toAbsolutePath().toString());
   assertEquals(503,assertThrows(ContractProblemException.class,inspector::requireVerifiedVideoAvailable).getStatus().value());
  } finally {java.nio.file.Files.deleteIfExists(file);}
 }
 @Test void decodesJpegAndPngAndRejectsSpoofedMimeAndBrokenBytes() throws Exception {
  var inspector=new ManagedArticleMediaInspector(mock(CosService.class),new ObjectMapper());
  for(String format:new String[]{"png","jpeg"}) {
   Path path=Files.createTempFile("article-image-test-","."+format);
   try { ImageIO.write(new BufferedImage(2,3,BufferedImage.TYPE_INT_RGB),format,path.toFile());
    var metadata=inspector.image(path,Files.size(path),"image/"+format); assertEquals(2,metadata.width()); assertEquals(3,metadata.height());
    assertThrows(ContractProblemException.class,()->inspector.image(path,Files.size(path),"image/webp"));
   } finally { Files.deleteIfExists(path); }
  }
  Path broken=Files.createTempFile("article-broken-",".png");
  try { Files.writeString(broken,"This is not an image"); assertThrows(ContractProblemException.class,()->inspector.image(broken,Files.size(broken),"image/png")); }
  finally { Files.deleteIfExists(broken); }
 }
 @Test void webpIsActuallyDecodedThroughRegisteredImageIOProvider() throws Exception {
  var file=new org.springframework.core.io.ClassPathResource("article-media.webp").getFile().toPath();
  var metadata=new ManagedArticleMediaInspector(mock(CosService.class),new ObjectMapper()).image(file,Files.size(file),"image/webp");
  assertEquals("image/webp",metadata.contentType()); assertEquals(2,metadata.width()); assertEquals(2,metadata.height());
 }
 @Test void rejectsMismatchedRealSizeAndInvalidVideoHeader() throws Exception {
  var cos=mock(CosService.class); var inspector=new ManagedArticleMediaInspector(cos,new ObjectMapper());
  doAnswer(i->{Files.writeString(i.getArgument(1),"bytes"); return null;}).when(cos).downloadObject(anyString(),any(Path.class),anyLong());
  assertEquals(400,assertThrows(ContractProblemException.class,()->inspector.inspect("article-resource/9/a","IMAGE","image/png",999)).getStatus().value());
  assertEquals(400,assertThrows(ContractProblemException.class,()->inspector.inspect("article-resource/9/b","VIDEO","video/mp4",5)).getStatus().value());
 }
 @Test void disabledDeepInspectionAcceptsMp4HeaderWithoutInventingPlaybackMetadata() throws Exception {
  var cos=mock(CosService.class); var inspector=new ManagedArticleMediaInspector(cos,new ObjectMapper());
  byte[] header={0,0,0,24,'f','t','y','p','i','s','o','m'};
  doAnswer(i->{Files.write(i.getArgument(1),header); return null;}).when(cos).downloadObject(anyString(),any(Path.class),anyLong());
  var metadata=inspector.inspect("article-resource/9/b","VIDEO","video/mp4",header.length);
  assertEquals("video/mp4",metadata.contentType()); assertNull(metadata.width()); assertNull(metadata.height()); assertNull(metadata.durationSeconds());
  var attachment=cn.jualn.miniapp.module.media.bo.MediaAttachmentBO.builder().id(1L).kind("VIDEO")
    .contentType(metadata.contentType()).url("https://media.example/video").build();
  var json=new ObjectMapper().valueToTree(new cn.jualn.miniapp.module.media.converter.AttachmentConverter().toVO(attachment));
  assertEquals(1,json.path("playback").size()); assertEquals("video/mp4",json.path("playback").path("contentType").asText());
  org.springframework.test.util.ReflectionTestUtils.setField(inspector,"ffprobePath",Path.of("target/nonexistent-ffprobe").toAbsolutePath().toString());
  assertEquals(503,assertThrows(ContractProblemException.class,()->inspector.inspect("article-resource/9/b","VIDEO","video/mp4",header.length)).getStatus().value());
 }
}
