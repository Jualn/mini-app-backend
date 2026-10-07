package cn.jualn.miniapp.module.search.converter;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.mapper.EnumConverter;
import cn.jualn.miniapp.module.search.bo.SearchPageBO;
import cn.jualn.miniapp.module.search.bo.SearchResultBO;
import cn.jualn.miniapp.module.search.bo.SearchTabCountBO;
import cn.jualn.miniapp.module.search.dto.request.SearchPageQuery;
import cn.jualn.miniapp.module.search.vo.SearchResultVO;
import cn.jualn.miniapp.module.search.vo.SearchTabCountVO;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring" ,uses = {EnumConverter.class})
public interface SearchConverter {
    java.util.List<cn.jualn.miniapp.module.search.vo.SearchActivityVO> toActivityVOList(
            java.util.List<cn.jualn.miniapp.module.activity.bo.ActivityListBO> values);


    SearchPageBO toPageBO(SearchPageQuery query);

    List<SearchResultVO> toVOList(List<SearchResultBO> list);

    List<SearchTabCountVO> toTabVOList(List<SearchTabCountBO> list);
}

