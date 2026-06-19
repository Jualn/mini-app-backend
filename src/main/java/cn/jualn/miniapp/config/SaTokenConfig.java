package cn.jualn.miniapp.config;

import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import cn.dev33.satoken.stp.StpLogic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 配置
 * 使用 JWT Simple 模式：无状态，token 自包含用户信息，不需要 Redis 存 Session
 * 适合小程序场景，服务端不存 token，直接解析验证
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

    /**
     * 使用 JWT Simple 模式替换默认 Session 模式
     */
    @Bean
    public StpLogic getStpLogicJwt() {
        return new StpLogicJwtForSimple();
    }
}
