package cn.jualn.miniapp.module.activity.service.impl;

import cn.jualn.miniapp.module.activity.service.ActivityFormPolicy;
import cn.jualn.miniapp.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import static cn.jualn.miniapp.module.activity.service.ActivityFormPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class ActivityFormPolicyTest {
    static final String SCHEMA = """
        {"fields":[
          {"key":"student","label":"学号","type":"text","required":true,"maxLength":10},
          {"key":"note","label":"备注","type":"textarea","required":false},
          {"key":"n","label":"数字","type":"number","required":false,"min":0,"max":10},
          {"key":"s","label":"单选","type":"single_select","required":false,"options":[{"value":"a","label":"甲"}]},
          {"key":"m","label":"多选","type":"multi_select","required":false,"options":[{"value":"a","label":"甲"},{"value":"b","label":"乙"}]},
          {"key":"c","label":"确认","type":"checkbox","required":true}
        ]}
        """;
    @Test void allTypesAndOptionalAbsence() {
        answers(parse(SCHEMA), parse("{\"student\":\"00123\",\"c\":true}"));
        answers(parse(SCHEMA), parse("{\"student\":\"00123\",\"c\":true,\"n\":1.5,\"s\":\"a\",\"m\":[\"b\",\"a\"],\"note\":null}"));
    }
    @Test void rejectsUnknownKeysRequiredTypesRangesAndDuplicateOptions() {
        for (String bad : new String[]{"{\"student\":123,\"c\":true}", "{\"student\":\"  \",\"c\":true}",
                "{\"student\":\"001\",\"c\":false}", "{\"student\":\"001\",\"c\":true,\"other\":1}",
                "{\"student\":\"001\",\"c\":true,\"n\":11}", "{\"student\":\"001\",\"c\":true,\"n\":\"1\"}",
                "{\"student\":\"001\",\"c\":true,\"n\":1e999}",
                "{\"student\":\"001\",\"c\":true,\"s\":\"b\"}", "{\"student\":\"001\",\"c\":true,\"m\":[\"a\",\"a\"]}",
                "{\"student\":\"001\",\"c\":true,\"m\":[2]}", "{\"student\":\"12345678901\",\"c\":true}"})
            assertThrows(BusinessException.class, () -> answers(parse(SCHEMA), parse(bad)), bad);
    }
    @Test void boundedDefinitionRejectsScriptsUnknownRulesAndDuplicateKeys() {
        assertThrows(BusinessException.class, () -> schema(parse(SCHEMA.replace("\"min\":0", "\"script\":\"run\""))));
        assertThrows(BusinessException.class, () -> schema(parse(SCHEMA.replace("\"key\":\"note\"", "\"key\":\"student\""))));
        assertThrows(BusinessException.class, () -> schema(parse(SCHEMA.replace("\"value\":\"b\"", "\"value\":\"a\""))));
        assertThrows(BusinessException.class, () -> schema(parse(SCHEMA.replace("\"maxLength\":10", "\"maxLength\":10001"))));
        assertThrows(BusinessException.class, () -> answers(parse(SCHEMA), parse("{\"note\":\"" + "中".repeat(22000) + "\"}")));
    }
    @Test void displayPreservesDefinitionOrderAndMissingOptional() {
        JsonNode fields = parse(SCHEMA).get("fields");
        assertEquals("甲、乙", display(fields.get(4), parse("[\"b\",\"a\"]")));
        assertEquals("", display(fields.get(5), null));
        assertEquals("否", display(fields.get(5), parse("false")));
    }
    @Test void xlsxKeepsStringsAndNeverCreatesFormulaCells() throws Exception {
        var r = new cn.jualn.miniapp.module.activity.entity.ActivityRegistration();
        r.setId(9007199254740993L);r.setActivityId(1L);r.setUserId(2L);r.setStatus(1);
        r.setFormData("{\"student\":\"00123\",\"note\":\"=HYPERLINK(1)\",\"m\":[\"b\",\"a\"],\"c\":true}");
        byte[] file = ActivityRegistrationExport.xlsx(parse(SCHEMA.replace("学号", "=学号")), java.util.List.of(r), java.time.LocalDateTime.now());
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(file))) {
            var row = book.getSheetAt(0).getRow(1);
            assertEquals("9007199254740993", row.getCell(0).getStringCellValue());
            assertEquals("00123", row.getCell(10).getStringCellValue());
            assertEquals("=HYPERLINK(1)", row.getCell(11).getStringCellValue());
            assertEquals("", row.getCell(12).getStringCellValue());
            assertEquals("甲、乙", row.getCell(14).getStringCellValue());
            for (var sheetRow : book.getSheetAt(0)) for (var cell : sheetRow)
                assertEquals(org.apache.poi.ss.usermodel.CellType.STRING, cell.getCellType());
        }
    }
    @Test void realJacksonDistinguishesOmittedAndExplicitNullSchema() throws Exception {
        var mapper = new cn.jualn.miniapp.config.JacksonConfig().objectMapper(new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder());
        var type = cn.jualn.miniapp.module.activity.dto.admin.AdminActivitySaveRequest.class;
        assertNull(mapper.readValue("{}", type).getFormSchema());
        assertTrue(mapper.readValue("{\"formSchema\":null}", type).getFormSchema().isNull());
    }
    @Test void fieldAndOptionCountsAreBoundedAndWindowUsesExclusiveCutoff() {
        var fields = new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();
        for (int i = 0; i < 31; i++) fields.add(parse("{\"key\":\"f" + i + "\",\"label\":\"题目\",\"type\":\"text\",\"required\":false}"));
        var definition = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().set("fields", fields);
        assertThrows(BusinessException.class, () -> schema(definition));
        var options = new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();
        for (int i = 0; i < 51; i++) options.add(parse("{\"value\":\"v" + i + "\",\"label\":\"选项\"}"));
        var choice = (com.fasterxml.jackson.databind.node.ObjectNode) parse(SCHEMA).get("fields").get(3);
        choice.set("options", options);
        fields.removeAll(); fields.add(choice);
        assertThrows(BusinessException.class, () -> schema(definition));
        var gate = new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(true);
        var now = java.time.LocalDateTime.of(2026,9,9,12,0);
        assertEquals("CLOSED", gate.registrationStatus(1, 2, null, 0, now, 2, now));
        assertEquals("OPEN", gate.registrationStatus(1, 4, now, 2, now.plusHours(1), 2, now));
        assertEquals("UNAVAILABLE", new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(false).registrationStatus(1, 2, null, 0, now.plusDays(1), 2, now));
    }
}
