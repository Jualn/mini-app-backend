package cn.jualn.miniapp.module.person.service.roster;

import cn.jualn.miniapp.module.person.service.facts.PersonCatalog;
import cn.jualn.miniapp.module.person.service.support.PersonnelValues;

import cn.jualn.miniapp.module.person.bo.QualificationBO.Column;
import cn.jualn.miniapp.module.person.bo.QualificationBO.HeaderColumn;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Mapping;
import cn.jualn.miniapp.module.person.bo.QualificationBO.ParsedRow;
import cn.jualn.miniapp.module.person.bo.QualificationBO.PrivateValues;
import cn.jualn.miniapp.module.person.bo.QualificationBO.RowError;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Sheet;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.invalid;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.problem;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.reason;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.text;

/** Literal XLSX only. Never invokes a formula evaluator or fetches links. */
@Component
public class RosterWorkbook {
    public static final int MAX_BYTES=10*1024*1024;
    private static RuntimeException invalidWorkbook() {return problem(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"roster-import-invalid");}
    private XSSFWorkbook open(byte[] bytes) {
        if(bytes==null || bytes.length==0 || bytes.length>MAX_BYTES) throw invalidWorkbook();
        try {
            long total=0;int entries=0;
            try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
                ZipEntry entry;var buffer=new byte[8192];
                while((entry=zip.getNextEntry())!=null) {
                    if(++entries>2000 || entry.getName().toLowerCase(Locale.ROOT).contains("vbaproject")) throw invalidWorkbook();
                    int read;while((read=zip.read(buffer))!=-1) {total+=read;if(total>50L*1024*1024) throw invalidWorkbook();}
                }
            }
            if(entries==0) throw invalidWorkbook();
            var workbook=new XSSFWorkbook(new ByteArrayInputStream(bytes));
            try {
                if(!workbook.getExternalLinksTable().isEmpty()) throw invalidWorkbook();
                int cells=0,rows=0;
                for(var sheet:workbook) for(var row:sheet) {
                    if(++rows>10001*workbook.getNumberOfSheets()) throw invalidWorkbook();
                    for(var cell:row) {
                        var visible=literal(cell);
                        if(++cells>1000000 || cell.getHyperlink()!=null || visible.codePointCount(0,visible.length())>2000) throw invalidWorkbook();
                    }
                }
                return workbook;
            } catch(RuntimeException failure) {workbook.close();throw failure;}
        } catch(cn.jualn.miniapp.common.exception.ContractProblemException e) {throw e;}
        catch(Exception e) {throw invalidWorkbook();}
    }
    public List<Sheet> sheets(byte[] bytes) {
        try(var w=open(bytes)) {
            if(w.getNumberOfSheets()==0) throw invalidWorkbook();
            var result=new ArrayList<Sheet>();
            for(int s=0;s<w.getNumberOfSheets();s++) result.add(new Sheet(s,w.getSheetName(s),w.isSheetHidden(s)||w.isSheetVeryHidden(s)));
            return List.copyOf(result);
        } catch(IOException e) {throw invalidWorkbook();}
    }
    public List<Column> columns(byte[] bytes) {
        try(var w=open(bytes)) {
            var result=new ArrayList<Column>();
            for(int s=0;s<w.getNumberOfSheets();s++) for(var c:headers(w,s,1)) result.add(new Column(s,c.columnIndex(),c.header()));
            return List.copyOf(result);
        } catch(IOException e) {throw invalidWorkbook();}
    }
    public List<HeaderColumn> headers(byte[] bytes,int sheet,int row) {
        try(var w=open(bytes)) {return headers(w,sheet,row);} catch(IOException e) {throw invalidWorkbook();}
    }
    private static List<HeaderColumn> headers(XSSFWorkbook w,int sheet,int row) {
        if(sheet<0 || sheet>=w.getNumberOfSheets()) throw invalid("sheetIndex");
        if(row<1 || row>1048576) throw invalid("headerRow");
        var source=w.getSheetAt(sheet);int last=0;
        for(var r:source) last=Math.max(last,r.getLastCellNum());
        var header=source.getRow(row-1);var result=new ArrayList<HeaderColumn>();
        for(int c=0;c<last;c++) {
            var cell=header==null?null:header.getCell(c);String type=cellType(cell);
            String title=Set.of("TEXT","NUMBER","BOOLEAN").contains(type)?literal(cell):"";
            result.add(new HeaderColumn(c+1,title,type,!title.isBlank()));
        }
        return List.copyOf(result);
    }
    public List<ParsedRow> parse(byte[] bytes,Mapping mapping,PersonCatalog catalog) {
        validate(mapping,catalog);
        try(var w=open(bytes)) {
            if(mapping.sheetIndex()>=w.getNumberOfSheets()) throw invalid("sheetIndex");
            var sheet=w.getSheetAt(mapping.sheetIndex());
            if(sheet.getRow(mapping.headerRow()-1)==null) throw invalid("headerRow");
            // Explicit, distinct column numbers resolve duplicate titles; never select a first match.
            var stages=new HashMap<String,String>();mapping.stageValues().forEach(s->stages.put(s.cellValue().strip(),s.stageId()));
            var rows=new ArrayList<ParsedRow>();var c=mapping.columns();
            for(int n=mapping.headerRow();n<=sheet.getLastRowNum();n++) {
                var row=sheet.getRow(n);if(row==null || blank(row)) continue;
                if(rows.size()>=10000) throw invalidWorkbook();
                var errors=new ArrayList<RowError>();
                String name=value(row,c.realName(),"realName",100,errors),number=student(row,c.studentNumber(),errors),
                    majorClass=value(row,c.majorClass(),"majorClass",200,errors),raw=value(row,c.stageId(),"stageId",100,errors);
                String stage=stages.get(raw);if(stage==null && !raw.isBlank()) throw invalid("stageValues");
                if(stage==null) errors.add(new RowError("stageId","UNKNOWN_STAGE"));
                rows.add(new ParsedRow(n+1,new PrivateValues(name,number,majorClass),stage,errors));
            }
            var duplicates=new HashMap<String,Integer>();rows.stream().filter(r->r.errors().stream().noneMatch(e->"studentNumber".equals(e.field()))).forEach(r->duplicates.merge(r.values().studentNumber().strip(),1,Integer::sum));
            for(var r:rows) if(duplicates.getOrDefault(r.values().studentNumber().strip(),0)>1) r.errors().add(new RowError("studentNumber","DUPLICATE"));
            return rows;
        } catch(IOException e) {throw invalidWorkbook();}
    }
    private static boolean blank(Row row) {for(var c:row) if(!literal(c).isBlank()) return false;return true;}
    private static String cellType(Cell cell) {
        if(cell==null) return "BLANK";
        return switch(cell.getCellType()) {
            case STRING -> "TEXT";case NUMERIC -> DateUtil.isCellDateFormatted(cell)?"DATE":"NUMBER";
            case BOOLEAN -> "BOOLEAN";case FORMULA -> "FORMULA";case ERROR -> "ERROR";default -> "BLANK";
        };
    }
    private static String literal(Cell cell) {
        if(cell==null || cell.getCellType()==CellType.BLANK) return "";
        if(cell.getCellType()==CellType.STRING) return cell.getStringCellValue();
        if(cell.getCellType()==CellType.FORMULA) return "="+cell.getCellFormula();
        return new DataFormatter(Locale.ROOT).formatCellValue(cell);
    }
    private static String student(Row row,int column,List<RowError> errors) {
        var cell=row.getCell(column-1);String visible=literal(cell);String type=cellType(cell);
        if(visible.isBlank()) {errors.add(new RowError("studentNumber","REQUIRED"));return visible;}
        String reason=null;
        if("TEXT".equals(type)) {
            if(visible.codePointCount(0,visible.length())>100) errors.add(new RowError("studentNumber","TOO_LONG"));
            return visible;
        }
        if("DATE".equals(type)) reason="DATE_CELL";
        else if("FORMULA".equals(type)) reason="FORMULA_CELL";
        else if("ERROR".equals(type)) reason="ERROR_CELL";
        else if(!"NUMBER".equals(type)) reason="UNSUPPORTED_CELL_TYPE";
        else {
            double number=cell.getNumericCellValue();
            if(number<0) reason="NEGATIVE_NUMBER";
            else if(!Double.isFinite(number) || number>999999999999999d) reason="NUMERIC_TOO_LONG";
            else if(number!=Math.rint(number)) reason="DECIMAL_NUMBER";
            else {
                String decimal=java.math.BigDecimal.valueOf(number).toBigIntegerExact().toString();
                String shownDigits=visible.split("[.Ee]",2)[0].replaceAll("[^0-9]","");
                if(shownDigits.length()>decimal.length() && shownDigits.startsWith("0") && new java.math.BigInteger(shownDigits).toString().equals(decimal)) reason="NUMERIC_LEADING_ZERO";
                else return decimal;
            }
        }
        String detail=switch(reason) {
            case "NEGATIVE_NUMBER" -> "学号不能为负数，请改为正确文本";
            case "NUMERIC_TOO_LONG" -> "数字学号超过15位，请从原始来源改为文本";
            case "NUMERIC_LEADING_ZERO" -> "需要前导零的学号请使用文本，不能通过数字格式补零";
            case "DECIMAL_NUMBER" -> "小数不能作为学号，请改为文本";
            case "DATE_CELL" -> "日期不能作为学号，请改为文本";
            case "FORMULA_CELL" -> "公式不能作为学号，请核对后粘贴为文本值";
            case "ERROR_CELL" -> "学号单元格有错误，请修正为文本";
            default -> "学号仅支持文本或不超过15位的非负整数";
        };
        errors.add(new RowError("studentNumber","INVALID_CELL",column,reason,detail));return visible;
    }
    private static String value(Row row,int column,String field,int max,List<RowError> errors) {
        var cell=row.getCell(column-1);String value=literal(cell).strip();
        if(value.isBlank()) {if(!"majorClass".equals(field)) errors.add(new RowError(field,"REQUIRED"));return "";}
        if(cell.getCellType()!=CellType.STRING) {errors.add(new RowError(field,"INVALID_CELL"));return "";}
        if(value.codePointCount(0,value.length())>max) {errors.add(new RowError(field,"TOO_LONG"));return "";}
        return value;
    }
    private static void validate(Mapping m,PersonCatalog catalog) {
        if(m==null || m.sheetIndex()<0 || m.headerRow()<1 || m.headerRow()>1048576 || m.columns()==null || m.stageValues()==null || m.stageValues().isEmpty() || m.stageValues().size()>4) throw invalid("mapping");
        var c=m.columns();var columns=List.of(c.realName(),c.studentNumber(),c.majorClass(),c.stageId());
        if(columns.stream().anyMatch(n->n<1 || n>16384) || columns.stream().distinct().count()!=4) throw invalid("columns");
        var seen=new HashSet<String>();for(var s:m.stageValues()) if(s==null || !seen.add(text(s.cellValue(),100,"cellValue")) || !catalog.confirmed(s.stageId())) throw invalid("stageValues");
    }
}
