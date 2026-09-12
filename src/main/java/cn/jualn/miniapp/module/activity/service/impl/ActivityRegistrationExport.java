package cn.jualn.miniapp.module.activity.service.impl;

import cn.jualn.miniapp.module.activity.entity.ActivityRegistration;
import cn.jualn.miniapp.module.activity.service.ActivityFormPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.List;

final class ActivityRegistrationExport {
    private ActivityRegistrationExport() {}
    static byte[] xlsx(JsonNode schema, List<ActivityRegistration> rows, LocalDateTime snapshotAt) {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("报名记录");
            CellStyle text = book.createCellStyle();
            text.setDataFormat(book.createDataFormat().getFormat("@"));
            Row header = sheet.createRow(0);
            String[] fixed = {"记录ID", "活动ID", "用户ID", "状态", "提交时间", "取消时间", "作废时间", "作废人ID", "作废原因", "导出时间"};
            int col = 0;
            for (String name : fixed) cell(header, col++, name, text);
            if (schema != null) for (JsonNode f : schema.get("fields")) cell(header, col++, f.get("label").asText() + " [" + f.get("key").asText() + "]", text);
            int index = 1;
            for (ActivityRegistration r : rows) {
                Row row = sheet.createRow(index++);
                Object[] values = {r.getId(), r.getActivityId(), r.getUserId(), switch (r.getStatus()) {case 1 -> "已提交"; case 2 -> "用户取消"; default -> "管理员作废";}, r.getSubmittedAt(), r.getCancelledAt(), r.getInvalidatedAt(), r.getInvalidatedBy(), r.getInvalidReason(), snapshotAt};
                col = 0;
                for (Object value : values) cell(row, col++, value == null ? "" : value.toString(), text);
                JsonNode data = ActivityFormPolicy.parse(r.getFormData());
                for (JsonNode f : schema.get("fields")) cell(row, col++, ActivityFormPolicy.display(f, data.get(f.get("key").asText())), text);
            }
            sheet.createFreezePane(0, 1);
            book.write(out); return out.toByteArray();
        } catch (java.io.IOException e) { throw new IllegalStateException("报名导出生成失败", e); }
    }
    private static void cell(Row row, int column, String value, CellStyle style) {
        // Explicit STRING cells (including headers) preserve leading zeroes and cannot execute formulas.
        Cell cell = row.createCell(column, CellType.STRING);
        cell.setCellStyle(style); cell.setCellValue(value);
    }
}
