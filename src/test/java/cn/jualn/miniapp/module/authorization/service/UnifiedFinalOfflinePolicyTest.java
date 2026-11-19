package cn.jualn.miniapp.module.authorization.service;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnifiedFinalOfflinePolicyTest {
    RbacOfflineTool.Request request(List<RbacOfflineTool.Target> targets) {
        return new RbacOfflineTool.Request(1,"f8dde4ca-bdf2-4dd2-8706-eae79c5f7579","synthetic-test",RbacOfflineTool.Kind.BOOTSTRAP,
            "operator","explicit approval","a".repeat(64),"2099-01-01T00:00:00Z","b".repeat(64),null,null,targets);
    }
    @Test void bootstrapAndRecoveryNormalizeOneExplicitRootOnly() {
        var first=new RbacOfflineTool.Target(1,"v1","c".repeat(64));var second=new RbacOfflineTool.Target(2,"v2","d".repeat(64));
        assertEquals(1,RbacOfflineTool.normalize(request(List.of(first))).targets().size());
        assertThrows(IllegalStateException.class,()->RbacOfflineTool.normalize(request(List.of(first,second))));
        assertThrows(IllegalStateException.class,()->RbacOfflineTool.normalize(request(List.of())));
    }
}
