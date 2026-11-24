package cn.jualn.miniapp.module.points.mapper;

import cn.jualn.miniapp.module.points.bo.PointAcceptanceSettings.Receipt;
import cn.jualn.miniapp.module.points.bo.PointAcceptanceSettings.Row;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PointAcceptanceSettingsMapper {
    Row setting(String organization);
    List<Row> settings(@Param("size") int size, @Param("offset") long offset);
    long count();
    List<Row> history(@Param("organization") String organization, @Param("size") int size, @Param("offset") long offset);
    long historyCount(String organization);
    Receipt receipt(@Param("actor") long actor, @Param("organization") String organization, @Param("key") String key);
    int insertEvidence(Receipt receipt);
    int insertReceipt(Receipt receipt);
    int pointToEvidence(Receipt receipt);
}
