package cn.jualn.miniapp.module.notify.mapper;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationDeliveryMapperSqlTest {

    @Test
    void deadJobRepairLimitsAnOrderedV2CandidateSetInsteadOfTheJoinedUpdate() throws Exception {
        Method method = NotificationDeliveryMapper.class.getMethod("repairDeadJobResults", int.class);
        String sql = String.join(" ", method.getAnnotation(Update.class).value())
                .replaceAll("\\s+", " ")
                .trim();

        int candidateOrder = sql.indexOf("ORDER BY MIN(j.dead_at), MIN(j.id)");
        int candidateJoin = sql.indexOf(") candidates ON candidates.delivery_id=d.id");
        int updateSet = sql.indexOf(" SET d.result_category=");

        assertTrue(sql.contains("j.schema_version=2"));
        assertTrue(sql.contains("j.subject_type='notification-delivery'"));
        assertTrue(candidateOrder > 0 && candidateOrder < candidateJoin);
        assertTrue(sql.substring(candidateOrder, candidateJoin).contains("LIMIT #{limit}"));
        assertFalse(sql.substring(updateSet).contains("ORDER BY"));
        assertFalse(sql.substring(updateSet).contains("LIMIT #{limit}"));
    }
}
