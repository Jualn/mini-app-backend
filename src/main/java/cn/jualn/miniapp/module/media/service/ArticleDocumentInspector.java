package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.module.article.service.ArticleBodyCodec;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Bounded passive parsing of public Article documents; never fetches relationships or executes content. */
public final class ArticleDocumentInspector {
    public static final String DOCX="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static String type(String kind,String name) {
        String lower=name==null?"":name.toLowerCase(Locale.ROOT);
        if("PDF".equals(kind) && lower.endsWith(".pdf")) return "application/pdf";
        if("WORD".equals(kind) && lower.endsWith(".docx")) return DOCX;
        if("WORD".equals(kind) && lower.endsWith(".doc")) return "application/msword";
        invalid();return null;
    }
    public String extract(Path file,String contentType) {
        try {
            if(Files.size(file)<1 || Files.size(file)>10485760L) invalid();
            String result;
            if("application/pdf".equals(contentType)) {
                try(var input=new org.apache.pdfbox.io.RandomAccessReadBufferedFile(file)) {
                  var parser=new org.apache.pdfbox.pdfparser.PDFParser(input);
                  try(var pdf=parser.parse(false)) {
                    if(pdf.isEncrypted() || pdf.getNumberOfPages()<1 || pdf.getNumberOfPages()>1000) invalid();
                    inspectPdf(pdf.getDocumentCatalog().getCOSObject(),Collections.newSetFromMap(new IdentityHashMap<>()),0,new long[1]);
                    var output=new LimitedText();
                    new org.apache.pdfbox.text.PDFTextStripper().writeText(pdf,output);
                    result=output.toString();
                  }
                }
            } else if(DOCX.equals(contentType)) {
                inspectZip(file);
                try(var input=Files.newInputStream(file);var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument(input);
                    var extractor=new org.apache.poi.xwpf.extractor.XWPFWordExtractor(doc)) {result=extractor.getText();}
            } else if("application/msword".equals(contentType)) {
                try(var input=Files.newInputStream(file);var fs=new org.apache.poi.poifs.filesystem.POIFSFileSystem(input)) {
                    inspectOle(fs.getRoot(),0);
                    try(var doc=new org.apache.poi.hwpf.HWPFDocument(fs);var extractor=new org.apache.poi.hwpf.extractor.WordExtractor(doc)) {result=extractor.getText();}
                }
            } else {invalid();return null;}
            if(result==null || result.length()>200000) invalid();
            return result.trim();
        } catch(cn.jualn.miniapp.common.exception.ContractProblemException e) {throw e;}
        catch(IOException | RuntimeException e) {invalid();return null;}
    }
    private void inspectOle(org.apache.poi.poifs.filesystem.DirectoryEntry directory,int depth) {
        if(depth>32) invalid();
        for(var entry:directory) {
            String name=entry.getName().toLowerCase(Locale.ROOT);
            if(Set.of("vba","macros","_vba_project_cur","encryptedpackage","encryptioninfo").contains(name)) invalid();
            if("objectpool".equals(name) && (!entry.isDirectoryEntry() || ((org.apache.poi.poifs.filesystem.DirectoryEntry)entry).getEntryCount()>0)) invalid();
            if(entry.isDirectoryEntry()) inspectOle((org.apache.poi.poifs.filesystem.DirectoryEntry)entry,depth+1);
        }
    }
    private void inspectZip(Path file) throws IOException {
        long total=0;int count=0;var names=new HashSet<String>();
        try(var zip=new ZipFile(file.toFile())) {
            var entries=zip.entries();
            while(entries.hasMoreElements()) {
                var entry=entries.nextElement();String name=entry.getName().toLowerCase(Locale.ROOT);
                if(++count>2048 || !names.add(name) || name.contains("..") || name.startsWith("/") || name.contains("\\")
                    || name.contains("vbaproject") || name.contains("/embeddings/") || name.contains("activex") || name.contains("altchunk")) invalid();
                try(var input=zip.getInputStream(entry)) {
                    byte[] bytes=input.readNBytes(10485761);total+=bytes.length;
                    if(bytes.length>10485760 || total>41943040) invalid();
                    var checksum=new CRC32();checksum.update(bytes);
                    if(entry.getSize()!=bytes.length || entry.getCrc()!=checksum.getValue()) invalid();
                    if(name.endsWith(".rels") || name.endsWith(".xml")) inspectXml(bytes);
                }
            }
            if(!names.contains("word/document.xml") || !names.contains("[content_types].xml")) invalid();
        }
    }
    private void inspectXml(byte[] bytes) {
        try {
            var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            var doc=factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
            var elements=doc.getElementsByTagName("*");
            for(int i=0;i<elements.getLength();i++) {
                var element=(org.w3c.dom.Element)elements.item(i);
                if("External".equalsIgnoreCase(element.getAttribute("TargetMode")) || element.getAttribute("ContentType").toLowerCase(Locale.ROOT).contains("macroenabled") || element.getAttribute("Type").endsWith("/aFChunk")) invalid();
            }
        } catch(Exception e) {invalid();}
    }
    private void inspectPdf(org.apache.pdfbox.cos.COSBase value,Set<org.apache.pdfbox.cos.COSBase> seen,int depth,long[] decoded) {
        if(value==null || !seen.add(value)) return;
        if(depth>128 || seen.size()>100000) invalid();
        if(value instanceof org.apache.pdfbox.cos.COSObject object) inspectPdf(object.getObject(),seen,depth+1,decoded);
        else if(value instanceof org.apache.pdfbox.cos.COSDictionary dictionary) {
            if(value instanceof org.apache.pdfbox.cos.COSStream stream) {
                try(var input=stream.createInputStream()) {
                    byte[] buffer=new byte[8192];int length;
                    while((length=input.read(buffer))!=-1) {decoded[0]+=length;if(decoded[0]>41943040) invalid();}
                } catch(IOException e) {invalid();}
            }
            for(var key:dictionary.keySet()) {
                if(Set.of("A","AA","OpenAction","JS","JavaScript","EmbeddedFiles","RichMedia","Launch","XFA").contains(key.getName())) invalid();
                inspectPdf(dictionary.getItem(key),seen,depth+1,decoded);
            }
        } else if(value instanceof org.apache.pdfbox.cos.COSArray array) for(var item:array) inspectPdf(item,seen,depth+1,decoded);
    }
    private static final class LimitedText extends Writer {
        private final StringBuilder value=new StringBuilder();
        @Override public void write(char[] chars,int offset,int length) {if(value.length()+length>200000) invalid();value.append(chars,offset,length);}
        @Override public void flush() {}
        @Override public void close() {}
        @Override public String toString() {return value.toString();}
    }
    private static void invalid() {ArticleBodyCodec.invalid("#/url","Use a complete, unencrypted PDF/DOC/DOCX without active or embedded content, within parsing limits");}
}
