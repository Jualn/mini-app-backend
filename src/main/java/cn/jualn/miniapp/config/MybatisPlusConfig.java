package cn.jualn.miniapp.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus configuration
 * 1. pagination plugin
 * 2. autofill created_at / updated_at / agreed_at
 * logical delete is handled by TableLogic + application.yml
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * Pagination plugin
     * DbType.MYSQL avoids generating invalid paging SQL
     * overflow=false returns empty results when the page exceeds the total
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor paginationInterceptor =
            new PaginationInnerInterceptor(DbType.MYSQL);
        paginationInterceptor.setOverflow(false);
        // Max 500 items per page to avoid abusive large pagination
        paginationInterceptor.setMaxLimit(500L);
        interceptor.addInnerInterceptor(paginationInterceptor);
        return interceptor;
    }

    /**
     * Autofill handler
     * TableField(fill = FieldFill. INSERT) fills created_at
     * TableField(fill = FieldFill.INSERT_UPDATE) fills updated_at
     *
     * 如果在数据库中设置了默认值（如 created_at DEFAULT CURRENT_TIMESTAMP，
     * updated_at DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP），
     * 则 就可以不管 mp的自动填充，否则在使用api时，如果不传入entity就不会触发自动更新
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
                this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
                this.strictInsertFill(metaObject, "agreedAt", LocalDateTime.class, now);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
