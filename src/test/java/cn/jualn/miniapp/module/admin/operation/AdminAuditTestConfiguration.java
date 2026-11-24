package cn.jualn.miniapp.module.admin.operation;
import cn.jualn.miniapp.module.admin.operation.mapper.AdminAuditMapper;
import cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal;
import cn.jualn.miniapp.module.person.service.profile.SubmissionNameReader;
import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import cn.jualn.miniapp.module.content.service.AdminQaContextReader;
import cn.jualn.miniapp.module.content.mapper.AdminContentMapper;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
/** Real durable journal for existing owner mechanism suites. Context projections use the same owner Mappers; management context callers must supply CurrentAuthorization. */
@Configuration
@Import(cn.jualn.miniapp.module.learningtask.service.LearningTaskSubmitterSummaryReader.class)
public class AdminAuditTestConfiguration {
    static <T> T mapper(SqlSessionFactory factory,Class<T> type) {
        if(!factory.getConfiguration().hasMapper(type))factory.getConfiguration().addMapper(type);
        return new SqlSessionTemplate(factory).getMapper(type);
    }
    @Bean @ConditionalOnMissingBean(AdminAuditJournal.class)
    AdminAuditJournal adminAuditJournal(SqlSessionFactory factory,ObjectMapper json) {return new AdminAuditJournal(mapper(factory,AdminAuditMapper.class),json);}
    @Bean @ConditionalOnMissingBean(SubmissionNameReader.class)
    SubmissionNameReader submissionNameReader(SqlSessionFactory factory) {return new SubmissionNameReader(mapper(factory,ManagedPersonMapper.class));}
    @Bean @ConditionalOnMissingBean(AdminQaContextReader.class)
    AdminQaContextReader adminQaContextReader(SqlSessionFactory factory,ObjectProvider<CurrentAuthorization> authorization) {
        return new AdminQaContextReader(mapper(factory,AdminContentMapper.class),authorization.getIfAvailable());
    }
}
