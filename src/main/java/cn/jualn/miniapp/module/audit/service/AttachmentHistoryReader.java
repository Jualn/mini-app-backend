package cn.jualn.miniapp.module.audit.service;
@org.springframework.stereotype.Service @lombok.RequiredArgsConstructor
public class AttachmentHistoryReader {
 private final cn.jualn.miniapp.module.audit.mapper.AttachmentHistoryMapper mapper;
 public boolean retained(long id,String url) {return mapper.retained(id,url.replace("\\","\\\\").replace("%","\\%").replace("_","\\_"));}
}
