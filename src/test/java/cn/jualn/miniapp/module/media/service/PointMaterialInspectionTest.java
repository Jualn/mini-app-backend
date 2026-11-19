package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.media.bo.PointMaterialBO.Declaration;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PointMaterialInspectionTest {
    @Test void privateVideoWorksWithoutDeepProbeButStillRejectsInvalidHeader() throws Exception {
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("probe", new ManagedArticleMediaInspector(org.mockito.Mockito.mock(cn.jualn.miniapp.third.cos.service.CosService.class), new com.fasterxml.jackson.databind.ObjectMapper()));
        var provider = new CosPointMaterialProvider(org.mockito.Mockito.mock(com.qcloud.cos.COSClient.class),
                org.mockito.Mockito.mock(cn.jualn.miniapp.third.cos.config.CosProperties.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), beans.getBeanProvider(PointMaterialSafety.class), "private-1250000000");
        provider.videoInspectors(beans.getBeanProvider(ManagedArticleMediaInspector.class));
        var path = Files.createTempFile("points-video-", ".mp4");
        try {
            Files.write(path, new byte[]{0,0,0,24,'f','t','y','p','i','s','o','m'});
            var declaration = new Declaration("proof.mp4", "VIDEO", "video/mp4", 12);
            assertDoesNotThrow(() -> provider.inspectVideo(path, declaration));
            Files.writeString(path, "not an mp4 file");
            assertEquals(400, assertThrows(ContractProblemException.class, () -> provider.inspectVideo(path, declaration)).getStatus().value());
        } finally { Files.deleteIfExists(path); }
    }
    @Test void privateVideoUsesTheExistingFormatProbeAndMapsUnavailableProbeToPrivateMedia() throws Exception {
        var probe = org.mockito.Mockito.mock(ManagedArticleMediaInspector.class);
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("probe", probe);
        var provider = new CosPointMaterialProvider(org.mockito.Mockito.mock(com.qcloud.cos.COSClient.class),
                org.mockito.Mockito.mock(cn.jualn.miniapp.third.cos.config.CosProperties.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), beans.getBeanProvider(PointMaterialSafety.class), "private-1250000000");
        provider.videoInspectors(beans.getBeanProvider(ManagedArticleMediaInspector.class));
        var path = Path.of("synthetic-local-video.mp4");
        var declaration = new Declaration("proof.mp4", "VIDEO", "video/mp4", 67);
        provider.inspectVideo(path, declaration);
        org.mockito.Mockito.verify(probe).video(path, 67);
        org.mockito.Mockito.doThrow(new ContractProblemException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "/problems/content-safety-check-unavailable", "synthetic missing ffprobe")).when(probe).video(path, 67);
        assertEquals("PRIVATE_MEDIA", assertThrows(ContractProblemException.class, () -> provider.inspectVideo(path, declaration)).getMessage());
    }
    @Test void decodesRealImagesAndRejectsSpoofedMime() throws Exception {
        var file=Files.createTempFile("points-image-",".png");
        try {
            ImageIO.write(new BufferedImage(2,3,BufferedImage.TYPE_INT_RGB),"png",file.toFile());
            CosPointMaterialProvider.inspect(file,new Declaration("proof.png","IMAGE","image/png",(int)Files.size(file)));
            assertEquals(400,assertThrows(ContractProblemException.class,()->CosPointMaterialProvider.inspect(file,new Declaration("proof.jpg","IMAGE","image/jpeg",(int)Files.size(file)))).getStatus().value());
        } finally { Files.deleteIfExists(file); }
    }
    @Test void parsesRealPdfAndDocxAndRejectsCrossTypeBytes() throws Exception {
        var pdf=Files.createTempFile("points-pdf-",".pdf");var word=Files.createTempFile("points-word-",".docx");
        try {
            try(var document=new PDDocument()) { document.addPage(new PDPage());document.save(pdf.toFile()); }
            try(var document=new XWPFDocument();var out=Files.newOutputStream(word)) { document.createParagraph().createRun().setText("Controlled evidence fixture");document.write(out); }
            CosPointMaterialProvider.inspect(pdf,new Declaration("proof.pdf","PDF","application/pdf",(int)Files.size(pdf)));
            CosPointMaterialProvider.inspect(word,new Declaration("proof.docx","WORD","application/vnd.openxmlformats-officedocument.wordprocessingml.document",(int)Files.size(word)));
            assertEquals(400,assertThrows(ContractProblemException.class,()->CosPointMaterialProvider.inspect(word,new Declaration("proof.pdf","PDF","application/pdf",(int)Files.size(word)))).getStatus().value());
        } finally { Files.deleteIfExists(pdf);Files.deleteIfExists(word); }
    }
}
