package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.module.media.mapper.MediaUploadRecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import cn.jualn.miniapp.module.media.entity.MediaUploadRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Stable opaque file URLs. No signed storage capability is persisted or returned to clients. */
@Service @RequiredArgsConstructor
public class MediaDeliveryUrls {
    public static final String PATH = "/v1/media/files/";
    private final MediaUploadRecordMapper records;
    @Value("${app.media.public-base-url:}") private String baseUrl;

    public String forKey(String key) {
        var row=records.selectOne(new LambdaQueryWrapper<MediaUploadRecord>().eq(MediaUploadRecord::getObjectKey,key));
        if(row==null || !key.equals(row.getObjectKey()) || !"VERIFIED".equals(row.getDeclaredKind())) throw unavailable();
        return base()+PATH+row.getId()+"/content";
    }
    public String adminVariant(String url) {
        if(url==null || !url.contains(PATH))return url;
        Long id=fileId(url);return id==null?url:base()+"/v1/admin/media/files/"+id+"/content";
    }
    public Long fileId(String url) {
        String prefix=base()+PATH;
        if(url==null || !url.startsWith(prefix) || !url.endsWith("/content")) return null;
        String id=url.substring(prefix.length(),url.length()-8);
        if(!id.matches("[1-9][0-9]{0,18}")) throw unavailable();
        try {return Long.valueOf(id);} catch(NumberFormatException e){throw unavailable();}
    }
    private String base() {
        if(baseUrl==null || baseUrl.isBlank()) throw unavailable();
        final java.net.URI uri;
        try {uri=java.net.URI.create(baseUrl);} catch(IllegalArgumentException invalid){throw unavailable();}
        if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null) throw unavailable();
        return baseUrl.replaceAll("/+$","");
    }
    private static cn.jualn.miniapp.common.exception.ContractProblemException unavailable() {return new cn.jualn.miniapp.common.exception.ContractProblemException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"/problems/service-unavailable","Controlled media URL unavailable");}
}
