package cn.jualn.miniapp.module.media.service;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.third.cos.service.CosService;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ManagedAttachmentInspectorTest {
 @Test void managedOfficialVideoRemainsAnOpaqueLinkAndUsesRealUploadedBytes() throws Exception {
  var cos=mock(CosService.class);var destination=new AtomicReference<Path>();byte[] bytes={0,0,0,20,'f','t','y','p','i','s','o','m'};
  doAnswer(i->{Path file=i.getArgument(1);destination.set(file);Files.write(file,bytes);return null;}).when(cos).downloadObject(eq("snapshot"),any(),anyLong());
  var value=new ManagedAttachmentInspector(cos).inspect("snapshot","LINK");
  assertEquals(bytes.length,value.sizeBytes());assertFalse(Files.exists(destination.get()));
 }
 @Test void declaredDocumentTypeIsCheckedAgainstTheFileAndTemporaryFileIsReleased() throws Exception {
  var cos=mock(CosService.class);var destination=new AtomicReference<Path>();
  doAnswer(i->{Path file=i.getArgument(1);destination.set(file);Files.writeString(file,"This is not a PDF");return null;}).when(cos).downloadObject(anyString(),any(),anyLong());
  assertEquals(400,assertThrows(ContractProblemException.class,()->new ManagedAttachmentInspector(cos).inspect("snapshot","PDF")).getStatus().value());
  assertFalse(Files.exists(destination.get()));
 }
 @Test void validPdfIsInspectedUsingTheExistingParser() throws Exception {
  var cos=mock(CosService.class);
  doAnswer(i->{try(var doc=new org.apache.pdfbox.pdmodel.PDDocument()) {doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());doc.save(((Path)i.getArgument(1)).toFile());}return null;}).when(cos).downloadObject(anyString(),any(),anyLong());
  var value=new ManagedAttachmentInspector(cos).inspect("snapshot","PDF");assertEquals("application/pdf",value.contentType());assertTrue(value.sizeBytes()>0);
 }
}
