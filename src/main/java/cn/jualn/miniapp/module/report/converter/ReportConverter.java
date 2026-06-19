package cn.jualn.miniapp.module.report.converter;

import cn.jualn.miniapp.common.mapper.EnumConverter;
import cn.jualn.miniapp.module.report.bo.ReportBO;
import cn.jualn.miniapp.module.report.bo.ReportCreateBO;
import cn.jualn.miniapp.module.report.bo.ReportHandleBO;
import cn.jualn.miniapp.module.report.bo.ReportPageBO;
import cn.jualn.miniapp.module.report.dto.request.ReportCreateRequest;
import cn.jualn.miniapp.module.report.dto.request.ReportHandleRequest;
import cn.jualn.miniapp.module.report.dto.request.ReportPageQuery;
import cn.jualn.miniapp.module.report.entity.Report;
import cn.jualn.miniapp.module.report.vo.ReportVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * 举报对象转换器。
 */
@Mapper(componentModel = "spring",uses = {EnumConverter.class})
public interface ReportConverter {

    ReportCreateBO toBO(ReportCreateRequest request);

    ReportHandleBO toBO(ReportHandleRequest request);

    @Mapping(target = "reporterId", ignore = true)
    ReportPageBO toBO(ReportPageQuery query);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "reporterId", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "handlerId", ignore = true)
    @Mapping(target = "handleRemark", ignore = true)
    @Mapping(target = "handledAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    Report toEntity(ReportCreateBO bo);

    ReportBO toBO(Report report);

    List<ReportBO> toBOList(List<Report> reports);

    ReportVO toVO(ReportBO bo);

    List<ReportVO> toVOList(List<ReportBO> list);
}


