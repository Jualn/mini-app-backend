package cn.jualn.miniapp.module.activity.service.impl;

import static cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.answers;
import static cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.parse;
import static cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.schema;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.jualn.miniapp.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

class ActivityFormPolicyTest {
    static final String SCHEMA = """
            {"allowModification":true,"fields":[
              {"fieldKey":"student","label":"学号","purpose":"STUDENT_NUMBER","type":"TEXT","required":true,"maxLength":10,"displayOrder":0},
              {"fieldKey":"note","label":"备注","purpose":"CUSTOM","type":"TEXT","required":false,"maxLength":100,"displayOrder":1},
              {"fieldKey":"track","label":"方向","purpose":"CUSTOM","type":"MULTI_SELECT","required":false,"options":[{"optionKey":"ai","label":"人工智能"},{"optionKey":"web","label":"Web"}],"displayOrder":2}
            ]}
            """;

    @Test
    void acceptsCanonicalDefinitionAndCompleteAnswerSet() {
        assertDoesNotThrow(() -> schema(parse(SCHEMA)));
        assertDoesNotThrow(() -> answers(parse(SCHEMA),
                parse("{\"student\":\"00123\",\"track\":[\"web\",\"ai\"]}")));
    }

    @Test
    void rejectsLegacyFieldShapesAndTypes() {
        assertThrows(BusinessException.class, () -> schema(parse(SCHEMA.replace(
                "\"fieldKey\":\"note\"", "\"key\":\"note\""))));
        assertThrows(BusinessException.class, () -> schema(parse(SCHEMA.replace(
                "\"type\":\"TEXT\"", "\"type\":\"textarea\""))));
        assertThrows(BusinessException.class, () -> schema(parse(SCHEMA.replace(
                "\"purpose\":\"CUSTOM\"", "\"purpose\":\"UNKNOWN\""))));
    }

    @Test
    void rejectsInvalidCanonicalAnswers() {
        for (String value : new String[]{
                "{}", "{\"student\":123}", "{\"student\":\"   \"}",
                "{\"student\":\"00123\",\"track\":[]}",
                "{\"student\":\"00123\",\"track\":[\"ai\",\"ai\"]}",
                "{\"student\":\"00123\",\"unknown\":\"x\"}"}) {
            assertThrows(BusinessException.class, () -> answers(parse(SCHEMA), parse(value)), value);
        }
    }

    @Test
    void enforcesContractBounds() {
        assertThrows(BusinessException.class, () -> schema(parse(SCHEMA.replace(
                "\"maxLength\":10", "\"maxLength\":2001"))));
        assertThrows(BusinessException.class, () -> schema(parse(SCHEMA.replace(
                "\"displayOrder\":0", "\"displayOrder\":-1"))));
        assertThrows(BusinessException.class, () -> schema(parse(SCHEMA.replace(
                "\"allowModification\":true,", ""))));
    }
}
