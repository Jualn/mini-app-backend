package cn.jualn.miniapp.module.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import cn.jualn.miniapp.module.content.vo.admin.AdminContentListVO;
import cn.jualn.miniapp.module.audit.vo.admin.AdminReviewItemVO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContentManagementTargetProjectionTest {
    @Test void optionalGrowthAndPlatformOrganizationStayAbsentWithTheProductionNullPolicy() {
        var json=new ObjectMapper().findAndRegisterModules().setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS);
        var content=new AdminContentListVO();
        content.setManagementTarget(new AdminContentListVO.ManagementTarget("ACTIVITY",new AdminContentListVO.BusinessTarget("PLATFORM",null)));
        var first=json.valueToTree(content);assertFalse(first.has("growthRecord"));assertEquals("ACTIVITY",first.path("managementTarget").path("namespace").asText());assertFalse(first.path("managementTarget").path("businessTarget").has("organizationId"));
        var review=new AdminReviewItemVO();review.setManagementTarget(new AdminReviewItemVO.ManagementTarget("POST",new AdminReviewItemVO.BusinessTarget("ORGANIZATION","fixed-org")));
        var second=json.valueToTree(review);assertFalse(second.has("growthRecord"));assertEquals("fixed-org",second.path("managementTarget").path("businessTarget").path("organizationId").asText());
    }
}
