package cn.jualn.miniapp.config;

import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.json.SaJsonTemplate;
import cn.dev33.satoken.json.SaJsonTemplateForJackson;
import cn.jualn.miniapp.module.user.enums.UserRole;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.jsontype.TypeIdResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 配置
 * 使用 JWT Simple 创建签名 Token；登录 mapping、Session 与 kickout 仍由 Sa-Token 存储维护。
 * 请求须同时通过 Sa 登录事实与当前数据库账号资格检查，不能仅凭 JWT 解析放行。
 * application.yml 对应配置：
 * sa-token:
 *   token-name: Authorization
 *   timeout: 2592000        # 30天，单位秒
 *   is-concurrent: true     # 允许同一账号多端登录
 *   is-share: false         # 每次登录生成新token
 *   jwt-secret-key: your-secret-key-min-32-chars
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    /** Read sessions written before UserRole moved to its owning module. */
    @Bean
    public SaJsonTemplate saJsonTemplate() {
        SaJsonTemplateForJackson template = new SaJsonTemplateForJackson();
        template.objectMapper.addHandler(new DeserializationProblemHandler() {
            @Override
            public JavaType handleUnknownTypeId(DeserializationContext context, JavaType baseType,
                    String subTypeId, TypeIdResolver resolver, String failureMessage) {
                if ("cn.jualn.miniapp.common.enums.UserRole".equals(subTypeId)
                        && baseType.getRawClass().isAssignableFrom(UserRole.class)) {
                    return context.constructType(UserRole.class);
                }
                return null;
            }
        });
        return template;
    }

    /**
     * 使用 JWT Simple 生成逻辑，保留 Sa-Token 登录映射及 Session 存储。
     */
    @Bean
    public StpLogic getStpLogicJwt() {
        return new StpLogicJwtForSimple();
    }
}
