package cn.jualn.miniapp.module.media.service;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.third.cos.service.CosService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.io.*;
@Service @RequiredArgsConstructor
public class ManagedAttachmentInspector {
 public record Metadata(String contentType,long sizeBytes) {}
 private final CosService cos;
 public Metadata inspect(String key,String kind) {
  Path file;
  try {file=Files.createTempFile("jualn-attachment-",".upload");}
  catch(IOException e) {throw AttachmentRegistrationService.unavailable(e);}
  try {
   cos.downloadObject(key,file,209715200L);
   long size=Files.size(file);if(size==0) invalid();
   String type;
   if(java.util.Set.of("IMAGE","POSTER","QR_CODE").contains(kind)) {
    try(var input=javax.imageio.ImageIO.createImageInputStream(file.toFile())) {
     var readers=javax.imageio.ImageIO.getImageReaders(input);if(!readers.hasNext()) {
      byte[] header;try(var bytes=Files.newInputStream(file)) {header=bytes.readNBytes(12);}
      if(header.length==12 && new String(header,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
        && new String(header,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP")) throw AttachmentRegistrationService.unavailable();
      invalid();
     }
     var reader=readers.next();try {reader.setInput(input);long pixels=(long)reader.getWidth(0)*reader.getHeight(0);
      if(pixels<=0 || pixels>40_000_000L || reader.read(0)==null) invalid();
      type="image/"+reader.getFormatName().toLowerCase(java.util.Locale.ROOT).replace("jpg","jpeg");
     } finally {reader.dispose();}
    }
   } else if("LINK".equals(kind)) {
    // Managed LINK is the existing opaque-file projection (including OfficialContent video).
    // It does not acquire Article's VIDEO identity, codec policy or playback fields.
    try(var input=new BufferedInputStream(Files.newInputStream(file))) {
     type=java.net.URLConnection.guessContentTypeFromStream(input);
     if(type==null) type="application/octet-stream";
    }
   } else if("PDF".equals(kind)) {
    try(var pdf=org.apache.pdfbox.Loader.loadPDF(file.toFile())) {if(pdf.getNumberOfPages()<1) invalid();}
    type="application/pdf";
   } else if("WORD".equals(kind)) {
    try(var stream=Files.newInputStream(file);var input=org.apache.poi.poifs.filesystem.FileMagic.prepareToCheckMagic(stream)) {
     var magic=org.apache.poi.poifs.filesystem.FileMagic.valueOf(input);
     if(magic==org.apache.poi.poifs.filesystem.FileMagic.OLE2) {try(var doc=new org.apache.poi.hwpf.HWPFDocument(input)) {doc.getRange();}type="application/msword";}
     else if(magic==org.apache.poi.poifs.filesystem.FileMagic.OOXML) {try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument(input)) {doc.getBodyElements();}type="application/vnd.openxmlformats-officedocument.wordprocessingml.document";}
     else {invalid();return null;}
    }
   } else {invalid();return null;}
   return new Metadata(type,size);
  } catch(IOException | org.apache.poi.UnsupportedFileFormatException e) {invalid();return null;}
  finally {if(file!=null) try {Files.deleteIfExists(file);}catch(IOException e) {throw AttachmentRegistrationService.unavailable();}}
 }
 private static void invalid() {throw ContractProblemException.validation(new ContractProblemException.Violation("body","#/url","INVALID_VALUE","Uploaded file does not match attachment kind or exceeds inspection limits"));}
}
