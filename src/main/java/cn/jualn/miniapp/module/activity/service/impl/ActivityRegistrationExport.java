package cn.jualn.miniapp.module.activity.service.impl;

import cn.jualn.miniapp.module.activity.entity.ActivityRegistration;
import cn.jualn.miniapp.module.activity.service.ActivityFormPolicy;
import cn.jualn.miniapp.common.exception.SystemException;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Objects;
import org.apache.poi.ss.util.CellRangeAddress;

final class ActivityRegistrationExport {
    static final int MAX_DATA_ROWS = 1_048_575;
    private static final DateTimeFormatter XLSX_TIME = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .appendLiteral(' ')
            .appendOffset("+HH:MM", "+00:00")
            .toFormatter();
    private ActivityRegistrationExport() {}
    static byte[] xlsx(String formVersion, JsonNode schema, List<ActivityRegistration> rows) {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            List<JsonNode> fields = orderedFields(schema);
            validateXlsxRows(formVersion, schema, rows);
            Sheet sheet = book.createSheet("报名名单");
            CellStyle text = book.createCellStyle();
            text.setDataFormat(book.createDataFormat().getFormat("@"));
            text.setWrapText(true);
            text.setVerticalAlignment(VerticalAlignment.TOP);
            CellStyle headerText = book.createCellStyle();
            headerText.cloneStyleFrom(text);
            Font headerFont = book.createFont();
            headerFont.setBold(true);
            headerText.setFont(headerFont);
            Row header = sheet.createRow(0);
            String[] fixed = {"报名状态", "提交时间", "取消时间"};
            int col = 0;
            for (String name : fixed) cell(header, col++, name, headerText);
            for (JsonNode field : fields) cell(header, col++, field.path("label").asText(), headerText);
            int index = 1;
            for (ActivityRegistration r : rows) {
                Row row = sheet.createRow(index++);
                col = 0;
                cell(row, col++, status(r), text);
                cell(row, col++, xlsxTime(r.getSubmittedAt()), text);
                cell(row, col++, xlsxTime(r.getCancelledAt()), text);
                JsonNode data = ActivityFormPolicy.parse(r.getFormData());
                for (JsonNode field : fields) {
                    cell(row, col++, xlsxDisplay(field, data.get(ActivityFormPolicy.fieldKey(field))), text);
                }
            }
            sheet.createFreezePane(0, 1);
            int lastColumn = fixed.length + fields.size() - 1;
            sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, rows.size()), 0, lastColumn));
            sheet.setColumnWidth(0, 12 * 256);
            sheet.setColumnWidth(1, 27 * 256);
            sheet.setColumnWidth(2, 27 * 256);
            for (int i = fixed.length; i <= lastColumn; i++) sheet.setColumnWidth(i, 24 * 256);
            book.write(out); return out.toByteArray();
        } catch (SystemException exception) {
            throw exception;
        } catch (RuntimeException | java.io.IOException exception) {
            throw new SystemException("报名 XLSX 生成失败", exception);
        }
    }
    static byte[] csv(JsonNode schema, List<ActivityRegistration> rows) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        List<String> headers = new java.util.ArrayList<>(List.of(
                "registrationId", "userId", "status", "submittedAt", "updatedAt", "cancelledAt", "formVersion"));
        List<JsonNode> fields = orderedFields(schema);
        for (JsonNode field : fields)
            headers.add(ActivityFormPolicy.fieldKey(field) + ":" + field.path("label").asText());
        line(csv, headers);
        for (ActivityRegistration row : rows) {
            List<String> values = new java.util.ArrayList<>(List.of(
                    row.getId().toString(), row.getUserId().toString(), row.getStatus() == 1 ? "SUBMITTED" : "CANCELLED",
                    time(row.getSubmittedAt()), time(row.getUpdatedAt()), time(row.getCancelledAt()),
                    row.getFormVersion() == null ? "" : row.getFormVersion()));
            JsonNode data = ActivityFormPolicy.parse(row.getFormData());
            for (JsonNode field : fields) {
                String key = ActivityFormPolicy.fieldKey(field); JsonNode answer = data == null ? null : data.get(key);
                String type = ActivityFormPolicy.fieldType(field);
                if (answer != null && "multi_select".equals(type)) {
                    var labels = new java.util.ArrayList<String>();
                    for (JsonNode selected : answer) for (JsonNode option : field.path("options"))
                        if (ActivityFormPolicy.optionKey(option).equals(selected.asText())) labels.add(option.path("label").asText());
                    values.add(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(labels).toString());
                } else values.add(ActivityFormPolicy.display(field, answer));
            }
            line(csv, values);
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private static List<JsonNode> orderedFields(JsonNode schema) {
        if (schema == null) return List.of();
        List<JsonNode> fields = new java.util.ArrayList<>();
        schema.path("fields").forEach(fields::add);
        fields.sort(java.util.Comparator.comparingInt((JsonNode field) -> field.path("displayOrder").asInt())
                .thenComparing(ActivityFormPolicy::fieldKey));
        return fields;
    }
    private static void validateXlsxRows(String formVersion, JsonNode schema, List<ActivityRegistration> rows) {
        if (rows.size() > MAX_DATA_ROWS) throw new SystemException("报名 XLSX 超过工作表行数上限");
        if (rows.isEmpty()) return;
        if (formVersion == null || schema == null) throw new SystemException("报名记录缺少冻结表单定义");
        for (ActivityRegistration row : rows) {
            if (!Objects.equals(formVersion, row.getFormVersion())) {
                throw new SystemException("报名记录 formVersion 无法解析: registrationId=" + row.getId()
                        + ", formVersion=" + row.getFormVersion());
            }
            try {
                ActivityFormPolicy.answers(schema, ActivityFormPolicy.parse(row.getFormData()));
            } catch (RuntimeException exception) {
                throw new SystemException("报名答案与冻结表单不一致: registrationId=" + row.getId(), exception);
            }
        }
    }
    private static String status(ActivityRegistration row) {
        if (Integer.valueOf(1).equals(row.getStatus())) return "已报名";
        if (Integer.valueOf(2).equals(row.getStatus())) return "已取消";
        throw new SystemException("XLSX 包含不支持的报名状态: registrationId=" + row.getId());
    }
    private static String xlsxTime(LocalDateTime value) {
        return value == null ? "" : value.atZone(ZoneId.of("Asia/Shanghai")).format(XLSX_TIME);
    }
    private static String xlsxDisplay(JsonNode field, JsonNode answer) {
        if (answer == null || answer.isNull()) return "";
        String type = ActivityFormPolicy.fieldType(field);
        if ("text".equals(type)) return answer.asText();
        if ("single_select".equals(type)) {
            for (JsonNode option : field.path("options")) {
                if (ActivityFormPolicy.optionKey(option).equals(answer.asText())) return option.path("label").asText();
            }
        } else if ("multi_select".equals(type)) {
            java.util.Set<String> selected = new java.util.HashSet<>();
            answer.forEach(value -> selected.add(value.asText()));
            List<String> labels = new java.util.ArrayList<>();
            for (JsonNode option : field.path("options")) {
                if (selected.contains(ActivityFormPolicy.optionKey(option))) labels.add(option.path("label").asText());
            }
            return String.join("\n", labels);
        }
        throw new SystemException("报名答案无法按冻结表单解释: fieldKey=" + ActivityFormPolicy.fieldKey(field));
    }
    private static String time(LocalDateTime value) { return value == null ? "" : value.atOffset(java.time.ZoneOffset.ofHours(8)).toString(); }
    private static void line(StringBuilder target, List<String> cells) { target.append(cells.stream().map(ActivityRegistrationExport::csvCell).collect(java.util.stream.Collectors.joining(","))).append("\r\n"); }
    private static String csvCell(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && ("=+-@\t\r".indexOf(safe.charAt(0)) >= 0)) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
    private static void cell(Row row, int column, String value, CellStyle style) {
        // Explicit STRING cells (including headers) preserve leading zeroes and cannot execute formulas.
        Cell cell = row.createCell(column, CellType.STRING);
        cell.setCellStyle(style); cell.setCellValue(value);
    }
}
