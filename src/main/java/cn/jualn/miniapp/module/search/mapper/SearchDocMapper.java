package cn.jualn.miniapp.module.search.mapper;

import cn.jualn.miniapp.module.search.bo.SearchResultBO;
import cn.jualn.miniapp.module.search.bo.SearchTabCountBO;
import cn.jualn.miniapp.module.search.entity.SearchDoc;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SearchDocMapper extends BaseMapper<SearchDoc> {

    int upsert(@Param("targetType") Integer targetType,
               @Param("targetId") Long targetId,
               @Param("searchText") String searchText,
               @Param("status") Integer status,
               @Param("publishedAt") LocalDateTime publishedAt);

    int deleteByTarget(@Param("targetType") Integer targetType,
                       @Param("targetId") Long targetId);

    List<SearchResultBO> selectSearchResults(@Param("keyword") String keyword,
                                             @Param("targetType") Integer targetType,
                                             @Param("lastPublishedAt") LocalDateTime lastPublishedAt,
                                             @Param("lastId") Long lastId,
                                             @Param("limit") Integer limit);

    List<SearchTabCountBO> selectTabCounts(@Param("keyword") String keyword);
}

