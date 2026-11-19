package cn.jualn.miniapp.module.post.service;
import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.media.enums.MediaType;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class PostContentRulesTest {
    @Test void pureImageIsContentButLinksAndTitleAreNotSubstitutes() {
        assertDoesNotThrow(()->PostContentRules.validate("",List.of(AttachmentItemBO.builder().type(MediaType.IMAGE).objectKey("verified-image").build())));
        assertThrows(ContractProblemException.class,()->PostContentRules.validate("",List.of(AttachmentItemBO.builder().type(MediaType.URL).url("https://example.invalid").build())));
        assertThrows(ContractProblemException.class,()->PostContentRules.validate("",List.of()));
    }
    @Test void textAndCompositeEmptyEditsKeepTheOriginalLengthBoundary() {
        assertDoesNotThrow(()->PostContentRules.validate("text",List.of()));
        assertThrows(ContractProblemException.class,()->PostContentRules.validate(" ",List.of()));
        assertThrows(ContractProblemException.class,()->PostContentRules.validate("x".repeat(5001),List.of()));
    }
}
