package cn.jualn.miniapp.common.annotation;

import cn.jualn.miniapp.common.enums.AuditScene;

import java.lang.annotation.*;

/**
 * 审核目标类型。
 *
 * <p>表示可以进行内容审核的业务对象类型，支持 post、activity、exam、comment 四种类型。
 * 每种类型需要实现对应的 XxxResultCallback 来处理审核完成后的业务逻辑。</p>
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditTarget {
    AuditScene value();
}
