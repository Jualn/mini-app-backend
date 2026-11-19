package cn.jualn.miniapp.module.person.service;

import cn.jualn.miniapp.module.person.service.facts.PersonCatalog;
import cn.jualn.miniapp.module.person.service.roster.RosterWorkbook;
import cn.jualn.miniapp.module.person.service.support.PersonPrivateData;

import cn.jualn.miniapp.module.person.bo.QualificationBO.*;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PeopleQualificationPolicyTest {
    static final String STAGE="PARTY_ACTIVIST";
    final RosterWorkbook reader=new RosterWorkbook();
    final PersonCatalog catalog=new PersonCatalog();
    Mapping mapping() {return new Mapping(0,1,new Columns(1,2,3,4),List.of(new StageValue("stage",STAGE)));}
    byte[] workbook(boolean number,boolean formula,boolean duplicate) throws Exception {
        try(var w=new XSSFWorkbook();var bytes=new ByteArrayOutputStream()) {
            var sheet=w.createSheet();var header=sheet.createRow(0);for(int i=0;i<4;i++) header.createCell(i).setCellValue("column"+i);
            for(int r=1;r<=(duplicate?2:1);r++) {var row=sheet.createRow(r);int i=0;for(var v:List.of("Name","000123","major class","stage")) row.createCell(i++).setCellValue(v);
                if(number) row.getCell(1).setCellValue(123);if(formula) row.getCell(1).setCellFormula("1+1");}
            w.write(bytes);return bytes.toByteArray();
        }
    }
    @Test void preservesTextLeadingZerosAndOnlyTrimsEdges() throws Exception {
        var rows=reader.parse(workbook(false,false,false),mapping(),catalog);assertEquals("000123",rows.get(0).values().studentNumber());assertTrue(rows.get(0).errors().isEmpty());
    }
    @Test void numericStudentCellsBecomeCompleteDecimalText() throws Exception {
        var row=reader.parse(workbook(true,false,false),mapping(),catalog).get(0);
        assertEquals("123",row.values().studentNumber());assertTrue(row.errors().isEmpty());
    }
    @Test void formulaIsAcceptedStructurallyButReportedWithoutCachedEvaluation() throws Exception {
        var bytes=workbook(false,true,false);assertFalse(reader.columns(bytes).isEmpty());
        var row=reader.parse(bytes,mapping(),catalog).get(0);
        assertEquals("=1+1",row.values().studentNumber());assertEquals("FORMULA_CELL",row.errors().get(0).reason());
    }
    @Test void duplicatesMarkEveryConflictingRow() throws Exception {
        var rows=reader.parse(workbook(false,false,true),mapping(),catalog);
        assertTrue(rows.stream().allMatch(r->r.errors().contains(new RowError("studentNumber","DUPLICATE"))));
    }
    @Test void mappingRequiresDistinctColumnsAndExplicitStages() throws Exception {
        var bytes=workbook(false,false,false);var invalid=new Mapping(0,1,new Columns(1,1,3,4),mapping().stageValues());
        assertThrows(ContractProblemException.class,()->reader.parse(bytes,invalid,catalog));
    }
    byte[] changeStudent(java.util.function.BiConsumer<XSSFWorkbook,org.apache.poi.ss.usermodel.Cell> change) throws Exception {
        try(var w=new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook(false,false,false)));var out=new ByteArrayOutputStream()) {
            change.accept(w,w.getSheetAt(0).getRow(1).getCell(1));w.write(out);return out.toByteArray();
        }
    }
    @Test void preservesRawWhitespaceAndSupportsZeroTenAndFifteenDigitIntegers() throws Exception {
        var raw=reader.parse(changeStudent((w,c)->c.setCellValue(" 000123 ")),mapping(),catalog).get(0);
        assertEquals(" 000123 ",raw.values().studentNumber());assertTrue(raw.errors().isEmpty());
        for(long n:new long[]{0,2026123456L,999999999999999L}) {
            var row=reader.parse(changeStudent((w,c)->c.setCellValue(n)),mapping(),catalog).get(0);
            assertEquals(Long.toString(n),row.values().studentNumber());assertTrue(row.errors().isEmpty());
        }
    }
    @Test void invalidNumericAndNonTextCellsExposeSpecificSafeReasons() throws Exception {
        var inputs=new LinkedHashMap<String,java.util.function.BiConsumer<XSSFWorkbook,org.apache.poi.ss.usermodel.Cell>>();
        inputs.put("NEGATIVE_NUMBER",(w,c)->c.setCellValue(-5));inputs.put("DECIMAL_NUMBER",(w,c)->c.setCellValue(1.5));
        inputs.put("NUMERIC_TOO_LONG",(w,c)->c.setCellValue(1000000000000000d));
        inputs.put("NUMERIC_LEADING_ZERO",(w,c)->{c.setCellValue(123);var style=w.createCellStyle();style.setDataFormat(w.createDataFormat().getFormat("000000"));c.setCellStyle(style);});
        inputs.put("DATE_CELL",(w,c)->{c.setCellValue(123);var style=w.createCellStyle();style.setDataFormat(w.createDataFormat().getFormat("yyyy-mm-dd"));c.setCellStyle(style);});
        inputs.put("FORMULA_CELL",(w,c)->c.setCellFormula("2026123456"));
        inputs.put("ERROR_CELL",(w,c)->c.setCellErrorValue(org.apache.poi.ss.usermodel.FormulaError.VALUE.getCode()));
        inputs.put("UNSUPPORTED_CELL_TYPE",(w,c)->c.setCellValue(true));
        for(var input:inputs.entrySet()) {
            var row=reader.parse(changeStudent(input.getValue()),mapping(),catalog).get(0);var error=row.errors().get(0);
            assertEquals("INVALID_CELL",error.code(),input.getKey());assertEquals(input.getKey(),error.reason());assertEquals(2,error.columnIndex());assertFalse(error.detail().isBlank());
        }
    }
    @Test void normalizedDuplicatesCannotBeHiddenByRawWhitespace() throws Exception {
        try(var w=new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook(false,false,true)));var out=new ByteArrayOutputStream()) {
            w.getSheetAt(0).getRow(2).getCell(1).setCellValue(" 000123 ");w.write(out);
            assertTrue(reader.parse(out.toByteArray(),mapping(),catalog).stream().allMatch(r->r.errors().contains(new RowError("studentNumber","DUPLICATE"))));
        }
    }
    @Test void directoriesAndDynamicHeadersPreservePositionsAndDoNotEvaluate() throws Exception {
        try(var w=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            w.createSheet("empty");var sheet=w.createSheet("data");w.setSheetHidden(1,true);
            var row=sheet.createRow(2);row.createCell(0).setCellValue(" duplicate ");row.createCell(2).setCellValue(" duplicate ");
            row.createCell(3).setCellFormula("1+1");row.createCell(4).setCellValue(12);row.createCell(5).setCellValue(true);
            w.write(out);var bytes=out.toByteArray();var sheets=reader.sheets(bytes);assertEquals(2,sheets.size());assertTrue(sheets.get(1).hidden());
            assertTrue(reader.headers(bytes,0,1).isEmpty());var headers=reader.headers(bytes,1,3);
            assertEquals(6,headers.size());assertEquals(" duplicate ",headers.get(0).header());assertEquals(headers.get(0).header(),headers.get(2).header());
            assertEquals("BLANK",headers.get(1).cellType());assertFalse(headers.get(1).usable());
            assertEquals("FORMULA",headers.get(3).cellType());assertEquals("",headers.get(3).header());assertFalse(headers.get(3).usable());
            assertEquals("12",headers.get(4).header());assertTrue(headers.get(5).usable());
            assertThrows(ContractProblemException.class,()->reader.headers(bytes,2,1));assertThrows(ContractProblemException.class,()->reader.headers(bytes,0,0));
        }
    }
    @Test void duplicateHeadersAreResolvedByExplicitDistinctColumnIndexes() throws Exception {
        try(var w=new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook(false,false,false)));var out=new ByteArrayOutputStream()) {
            w.getSheetAt(0).getRow(0).getCell(0).setCellValue("same");w.getSheetAt(0).getRow(0).getCell(1).setCellValue("same");w.write(out);
            assertTrue(reader.parse(out.toByteArray(),mapping(),catalog).get(0).errors().isEmpty());
        }
    }
    @Test void encryptionIsRandomizedAuthenticatedAndPurposeBound() {
        var a=new byte[32];var b=new byte[32];Arrays.fill(b,(byte)7);
        var store=new PersonPrivateData(Base64.getEncoder().encodeToString(a),Base64.getEncoder().encodeToString(b));var input="000123".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var first=store.encrypt("entry",input);assertFalse(Arrays.equals(first,store.encrypt("entry",input)));assertArrayEquals(input,store.decrypt("entry",first));
        assertThrows(ContractProblemException.class,()->store.decrypt("workbook",first));first[first.length-1]^=1;assertThrows(ContractProblemException.class,()->store.decrypt("entry",first));
        assertNotEquals(store.digest("student-number","000123"),store.digest("command","000123"));
    }
    @Test void missingKeysFailClosed() {var store=new PersonPrivateData("","");assertThrows(ContractProblemException.class,()->store.digest("student-number","000123"));assertThrows(ContractProblemException.class,()->store.encrypt("entry",new byte[1]));}
}
