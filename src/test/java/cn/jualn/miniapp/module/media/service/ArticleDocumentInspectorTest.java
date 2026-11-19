package cn.jualn.miniapp.module.media.service;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import cn.jualn.miniapp.common.exception.ContractProblemException;
class ArticleDocumentInspectorTest {
 @TempDir Path temporary;
 final ArticleDocumentInspector inspector=new ArticleDocumentInspector();
 @Test void realPdfAndDocxExtractTextAndRejectMimeDisguise() throws Exception {
  Path pdf=temporary.resolve("a.pdf");
  try(var document=new org.apache.pdfbox.pdmodel.PDDocument()) {
   var page=new org.apache.pdfbox.pdmodel.PDPage();document.addPage(page);
   try(var stream=new org.apache.pdfbox.pdmodel.PDPageContentStream(document,page)) {stream.beginText();stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);stream.showText("Public material");stream.endText();}document.save(pdf.toFile());
  }
  assertTrue(inspector.extract(pdf,"application/pdf").contains("Public material"));
  assertThrows(ContractProblemException.class,()->inspector.extract(pdf,ArticleDocumentInspector.DOCX));
  Path word=temporary.resolve("a.docx");
  try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument();var out=Files.newOutputStream(word)) {doc.createParagraph().createRun().setText("Document paragraph");doc.createTable().getRow(0).getCell(0).setText("Table text");doc.write(out);}
  var text=inspector.extract(word,ArticleDocumentInspector.DOCX);assertTrue(text.contains("Document paragraph"));assertTrue(text.contains("Table text"));
  assertThrows(ContractProblemException.class,()->inspector.extract(word,"application/msword"));
 }
 @Test void realLegacyDocExtractsWithoutTreatingItAsDocx() throws Exception {
  Path doc=temporary.resolve("legacy.doc");
  try(var input=getClass().getResourceAsStream("/article-document/simple.doc")) {assertNotNull(input);Files.copy(input,doc);}
  assertFalse(inspector.extract(doc,"application/msword").isBlank());
  assertThrows(ContractProblemException.class,()->inspector.extract(doc,ArticleDocumentInspector.DOCX));
 }
 @Test void encryptedActiveDamagedAndMacroDocumentsAreRejected() throws Exception {
  Path pdf=temporary.resolve("encrypted.pdf");
  try(var document=new org.apache.pdfbox.pdmodel.PDDocument()) {document.addPage(new org.apache.pdfbox.pdmodel.PDPage());document.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner","password",new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));document.save(pdf.toFile());}
  assertThrows(ContractProblemException.class,()->inspector.extract(pdf,"application/pdf"));
  Path active=temporary.resolve("active.pdf");
  try(var document=new org.apache.pdfbox.pdmodel.PDDocument()) {document.addPage(new org.apache.pdfbox.pdmodel.PDPage());document.getDocumentCatalog().setOpenAction(new org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript("alert(1)"));document.save(active.toFile());}
  assertThrows(ContractProblemException.class,()->inspector.extract(active,"application/pdf"));
  Path damaged=temporary.resolve("damaged.doc");Files.writeString(damaged,"not a document");
  for(String type:java.util.List.of("application/pdf","application/msword",ArticleDocumentInspector.DOCX)) assertThrows(ContractProblemException.class,()->inspector.extract(damaged,type));
  for(String entry:java.util.List.of("word/vbaProject.bin","word/embeddings/object.bin","../escape")) {
   Path macro=temporary.resolve(java.util.UUID.randomUUID()+".docx");
   try(var zip=new ZipOutputStream(Files.newOutputStream(macro))) {zip.putNextEntry(new ZipEntry(entry));zip.write(new byte[]{1});zip.closeEntry();}
   assertThrows(ContractProblemException.class,()->inspector.extract(macro,ArticleDocumentInspector.DOCX));
  }
 }
 @Test void extensionsAndZipResourceLimitsAreEnforced() throws Exception {
  assertEquals("application/pdf",ArticleDocumentInspector.type("PDF","资料.PDF"));
  assertThrows(ContractProblemException.class,()->ArticleDocumentInspector.type("WORD","macro.docm"));
  assertThrows(ContractProblemException.class,()->ArticleDocumentInspector.type("PDF","not.pdf.exe"));
  Path bomb=temporary.resolve("bomb.docx");try(var zip=new ZipOutputStream(Files.newOutputStream(bomb))) {zip.putNextEntry(new ZipEntry("word/document.xml"));zip.write(new byte[10485761]);zip.closeEntry();}
  assertThrows(ContractProblemException.class,()->inspector.extract(bomb,ArticleDocumentInspector.DOCX));
 }
}
