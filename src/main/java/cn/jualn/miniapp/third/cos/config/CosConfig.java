package cn.jualn.miniapp.third.cos.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.region.Region;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * COS 客户端配置。
 */
@Configuration
public class CosConfig {

    /**
     * 创建 COS SDK 客户端。
     *
     * @param properties COS 配置
     * @return COS 客户端实例
     */
    @Bean(destroyMethod = "shutdown")
    public COSClient cosSdkClient(CosProperties properties) {
        COSCredentials credentials = new BasicCOSCredentials(properties.getSecretId(), properties.getSecretKey());
        ClientConfig clientConfig = new ClientConfig(new Region(properties.getRegion()));
        return new COSClient(credentials, clientConfig);
    }
}
