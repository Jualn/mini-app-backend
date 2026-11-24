package cn.jualn.miniapp.module.notify.converter;
import cn.jualn.miniapp.module.notify.bo.NotificationCatalogBO;
import cn.jualn.miniapp.module.notify.vo.NotificationCatalogVO;
@org.mapstruct.Mapper(componentModel="spring")
public interface NotificationCatalogConverter {
    NotificationCatalogVO.Options options(NotificationCatalogBO.Options value);
    NotificationCatalogVO.Status status(NotificationCatalogBO.Status value);
    NotificationCatalogVO.Summary summary(NotificationCatalogBO.Summary value);
}
