package cn.jualn.miniapp.module.activity.service.impl;

import cn.jualn.miniapp.module.activity.entity.ActivityRegistration;
import cn.jualn.miniapp.module.activity.service.ActivityFormPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityRegistrationContractTest {
    private static final String FORM = """
            {"allowModification":true,"fields":[
              {"fieldKey":"student-number","label":"学号","purpose":"STUDENT_NUMBER","type":"TEXT","required":true,"maxLength":20,"displayOrder":0},
              {"fieldKey":"track","label":"方向","purpose":"CUSTOM","type":"MULTI_SELECT","required":false,"options":[{"optionKey":"ai","label":"人工智能"}],"displayOrder":1}
            ]}
            """;
    private static final String XLSX_FORM = """
            {"allowModification":true,"fields":[
              {"fieldKey":"single-internal","label":"报名类别","purpose":"CUSTOM","type":"SINGLE_SELECT","required":true,"options":[{"optionKey":"staff-key","label":"工作人员"}],"displayOrder":0},
              {"fieldKey":"phone-internal","label":"手机号","purpose":"PHONE","type":"TEXT","required":true,"maxLength":30,"displayOrder":1},
              {"fieldKey":"student-internal","label":"学号","purpose":"STUDENT_NUMBER","type":"TEXT","required":true,"maxLength":30,"displayOrder":2},
              {"fieldKey":"track-internal","label":"方向","purpose":"CUSTOM","type":"MULTI_SELECT","required":true,"options":[{"optionKey":"ai-key","label":"人工智能"},{"optionKey":"data-key","label":"数据科学"}],"displayOrder":3},
              {"fieldKey":"note-internal","label":"备注","purpose":"CUSTOM","type":"TEXT","required":true,"maxLength":100,"displayOrder":4}
            ]}
            """;

    @Test
    void cancelledPublishedActivityKeepsItsFormReadableWithoutOpeningParticipation() {
        var activities = org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.mapper.ActivityMapper.class);
        var registrations = org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.mapper.ActivityRegistrationMapper.class);
        var timeline = org.mockito.Mockito.mock(cn.jualn.miniapp.module.timeline.service.TimelineService.class);
        var availability = new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(true);
        var service = new ActivityRegistrationServiceImpl(activities, registrations, availability,
                new cn.jualn.miniapp.module.activity.service.ActivityParticipationPolicy(availability), timeline);
        org.mockito.Mockito.when(timeline.listTimelinesByTarget(
                cn.jualn.miniapp.common.enums.TargetType.ACTIVITY, 2L)).thenReturn(List.of());
        org.mockito.Mockito.when(activities.selectRegistrationActivity(2L)).thenReturn(
                cn.jualn.miniapp.module.activity.entity.Activity.builder().id(2L).publishStatus(1)
                        .lifecycleStatus(2).registrationMode(2).formVersion("form-v1").formSchema(FORM).build());
        var form = service.getForm(2L);
        org.junit.jupiter.api.Assertions.assertEquals("form-v1", form.formVersion());
        org.junit.jupiter.api.Assertions.assertEquals("UNAVAILABLE", form.registrationStatus());
    }

    @Test
    void cancellationChecksObservedIdentityAndVersionInsideTheService() {
        var activities = org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.mapper.ActivityMapper.class);
        var registrations = org.mockito.Mockito.mock(cn.jualn.miniapp.module.activity.mapper.ActivityRegistrationMapper.class);
        var availability = new cn.jualn.miniapp.module.activity.service.ActivityFormAvailability(true);
        var service = new ActivityRegistrationServiceImpl(activities, registrations, availability,
                new cn.jualn.miniapp.module.activity.service.ActivityParticipationPolicy(availability),
                org.mockito.Mockito.mock(cn.jualn.miniapp.module.timeline.service.TimelineService.class));
        org.mockito.Mockito.when(activities.lockRegistrationActivity(2L)).thenReturn(
                cn.jualn.miniapp.module.activity.entity.Activity.builder().id(2L).formSchema(FORM).build());
        ActivityRegistration row = new ActivityRegistration();
        row.setId(3L);
        row.setContractVersion(2L);
        org.mockito.Mockito.when(registrations.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(row);
        cn.jualn.miniapp.common.constant.UserContext.setUserId(7L);
        try {
            var missing = org.junit.jupiter.api.Assertions.assertThrows(
                    cn.jualn.miniapp.common.exception.ContractProblemException.class,
                    () -> service.cancelMineContract(2L, null));
            org.junit.jupiter.api.Assertions.assertEquals(428, missing.getStatus().value());
            org.junit.jupiter.api.Assertions.assertEquals("/problems/revision-required", missing.getType());
            var stale = org.junit.jupiter.api.Assertions.assertThrows(
                    cn.jualn.miniapp.common.exception.ContractProblemException.class,
                    () -> service.cancelMineContract(2L, new cn.jualn.miniapp.module.activity.bo.RegistrationVersionBO(3L, 1L)));
            org.junit.jupiter.api.Assertions.assertEquals(412, stale.getStatus().value());
            org.junit.jupiter.api.Assertions.assertEquals("/problems/revision-mismatch", stale.getType());
            org.junit.jupiter.api.Assertions.assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,
                    () -> service.cancelMineContract(2L, new cn.jualn.miniapp.module.activity.bo.RegistrationVersionBO(4L, 2L)));
        } finally {
            cn.jualn.miniapp.common.constant.UserContext.clear();
        }
    }

    @Test
    void acceptsCanonicalDefinitionAndProducesSafeBomCsv() {
        var schema = ActivityFormPolicy.parse(FORM);
        assertDoesNotThrow(() -> ActivityFormPolicy.schema(schema));
        assertDoesNotThrow(() -> ActivityFormPolicy.answers(schema,
                ActivityFormPolicy.parse("{\"student-number\":\"00123\",\"track\":[\"ai\"]}")));
        ActivityRegistration row = new ActivityRegistration();
        row.setId(1L); row.setActivityId(2L); row.setUserId(3L); row.setStatus(1);
        row.setFormVersion("activity-2-form-v1"); row.setSubmittedAt(LocalDateTime.of(2026, 9, 13, 10, 0));
        row.setUpdatedAt(row.getSubmittedAt()); row.setFormData("{\"student-number\":\"=1+1\",\"track\":[\"ai\"]}");
        String csv = new String(ActivityRegistrationExport.csv(schema, List.of(row)), StandardCharsets.UTF_8);
        assertEquals("\uFEFF\"registrationId\",\"userId\",\"status\",\"submittedAt\",\"updatedAt\",\"cancelledAt\",\"formVersion\",\"student-number:学号\",\"track:方向\"\r\n"
                + "\"1\",\"3\",\"SUBMITTED\",\"2026-09-13T10:00+08:00\",\"2026-09-13T10:00+08:00\",\"\",\"activity-2-form-v1\",\"'=1+1\",\"[\"\"人工智能\"\"]\"\r\n", csv);
        assertTrue(csv.startsWith("\uFEFF"));
        assertTrue(csv.contains("\"student-number:学号\""));
        assertTrue(csv.contains("\"'=1+1\""));
        assertTrue(csv.contains("\"[\"\"人工智能\"\"]\""));
    }

    @Test
    void xlsxUsesFrozenLabelsAndTextCellsWithoutExposingInternalKeys() throws Exception {
        ActivityRegistration row = new ActivityRegistration();
        row.setId(11L);
        row.setStatus(2);
        row.setFormVersion("activity-2-form-v1");
        row.setSubmittedAt(LocalDateTime.of(2026, 9, 13, 10, 0, 0, 123_000_000));
        row.setCancelledAt(LocalDateTime.of(2026, 9, 14, 11, 30));
        row.setFormData("""
                {"single-internal":"staff-key","phone-internal":"01380000000000000001",
                 "student-internal":"001234567890123456789","track-internal":["data-key","ai-key"],
                 "note-internal":"=HYPERLINK(\\\"https://example.test\\\")"}
                """);

        byte[] bytes = ActivityRegistrationExport.xlsx("activity-2-form-v1",
                ActivityFormPolicy.parse(XLSX_FORM), List.of(row));

        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = book.getSheetAt(0);
            assertEquals("报名名单", sheet.getSheetName());
            assertEquals(List.of("报名状态", "提交时间", "取消时间", "报名类别", "手机号", "学号", "方向", "备注"),
                    java.util.stream.IntStream.range(0, 8)
                            .mapToObj(index -> sheet.getRow(0).getCell(index).getStringCellValue()).toList());
            String allHeaders = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(index -> sheet.getRow(0).getCell(index).getStringCellValue())
                    .collect(java.util.stream.Collectors.joining("|"));
            assertFalse(allHeaders.contains("internal"));
            var values = sheet.getRow(1);
            assertEquals("已取消", values.getCell(0).getStringCellValue());
            assertEquals("2026-09-13 10:00:00.123 +08:00", values.getCell(1).getStringCellValue());
            assertEquals("2026-09-14 11:30:00 +08:00", values.getCell(2).getStringCellValue());
            assertEquals("工作人员", values.getCell(3).getStringCellValue());
            assertEquals("01380000000000000001", values.getCell(4).getStringCellValue());
            assertEquals("001234567890123456789", values.getCell(5).getStringCellValue());
            assertEquals("人工智能\n数据科学", values.getCell(6).getStringCellValue());
            assertEquals("=HYPERLINK(\"https://example.test\")", values.getCell(7).getStringCellValue());
            for (int column = 0; column < 8; column++) assertEquals(CellType.STRING, values.getCell(column).getCellType());
            assertNotNull(sheet.getPaneInformation());
            assertTrue(sheet.getPaneInformation().isFreezePane());
            assertNotNull(sheet.getCTWorksheet().getAutoFilter());
        }
    }

    @Test
    void xlsxFailsWhenRegistrationVersionCannotResolveFrozenDefinition() {
        ActivityRegistration row = new ActivityRegistration();
        row.setId(11L);
        row.setStatus(1);
        row.setFormVersion("activity-2-form-v0");
        row.setSubmittedAt(LocalDateTime.of(2026, 9, 13, 10, 0));
        row.setFormData("{}");

        assertThrows(cn.jualn.miniapp.common.exception.SystemException.class,
                () -> ActivityRegistrationExport.xlsx("activity-2-form-v1",
                        ActivityFormPolicy.parse(XLSX_FORM), List.of(row)));
    }

    @Test
    void xlsxFailsInsteadOfDisplayingUnknownOptionKey() {
        ActivityRegistration row = new ActivityRegistration();
        row.setId(12L);
        row.setStatus(1);
        row.setFormVersion("activity-2-form-v1");
        row.setSubmittedAt(LocalDateTime.of(2026, 9, 13, 10, 0));
        row.setFormData("""
                {"single-internal":"unknown-key","phone-internal":"01380000000000000001",
                 "student-internal":"001234567890123456789","track-internal":["ai-key"],
                 "note-internal":"普通文本"}
                """);

        assertThrows(cn.jualn.miniapp.common.exception.SystemException.class,
                () -> ActivityRegistrationExport.xlsx("activity-2-form-v1",
                        ActivityFormPolicy.parse(XLSX_FORM), List.of(row)));
    }

    @Test
    void emptyUnfrozenXlsxContainsOnlyThreeBusinessHeaders() throws Exception {
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(
                ActivityRegistrationExport.xlsx(null, null, List.of())))) {
            var header = book.getSheetAt(0).getRow(0);
            assertEquals(3, header.getPhysicalNumberOfCells());
            assertEquals("报名状态", header.getCell(0).getStringCellValue());
            assertEquals("提交时间", header.getCell(1).getStringCellValue());
            assertEquals("取消时间", header.getCell(2).getStringCellValue());
        }
    }
}
