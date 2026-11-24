package cn.jualn.miniapp.module.wx.notice;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
class WxNotificationTemplateRegistryTest {
    @Test void catalogIsDetachedAndFrozenMapsCannotBeMutated(){
        var p=WxNotificationTemplateTest.fixture();var registry=WxNotificationTemplateTest.registry(p);var first=registry.effective("start");
        p.getBindings().get("start").getFields().clear();assertFalse(first.fields().isEmpty());assertThrows(UnsupportedOperationException.class,()->first.fields().clear());
        assertSame(first,registry.resolve("ACTIVITY_START_REMINDER","WECHAT_MINI_PROGRAM"));assertNull(registry.resolve("ACTIVITY_REMIND","WECHAT_MINI_PROGRAM"));
    }
    @Test void removingOrInvalidatingCurrentBindingStopsOldSnapshot(){
        var p=WxNotificationTemplateTest.fixture();var original=WxNotificationTemplateTest.registry(p);var snapshot=new WxNotificationTemplateRenderer().renderBinding(original,"start",WxNotificationTemplateTest.frozen());
        assertTrue(original.isSnapshotEnabled(snapshot));p.getBindings().clear();assertFalse(WxNotificationTemplateTest.registry(p).isSnapshotEnabled(snapshot));
        p=WxNotificationTemplateTest.fixture();p.getBindings().get("start").setJumpTarget("unregistered");assertFalse(WxNotificationTemplateTest.registry(p).isSnapshotEnabled(snapshot));
    }
    @Test void invalidEntriesProduceControlledDiagnosticsRatherThanBreakingAllTemplates(){
        var p=WxNotificationTemplateTest.fixture();p.getDefinitions().get("fixture").getFields().get("thing1").setMaxLength(-1);
        var registry=WxNotificationTemplateTest.registry(p);assertNull(registry.resolve("ACTIVITY_START_REMINDER","WECHAT_MINI_PROGRAM"));
        assertEquals(java.util.List.of("MAPPING_INVALID"),registry.status("ACTIVITY_START_REMINDER","WECHAT_MINI_PROGRAM").issues());
    }
}
