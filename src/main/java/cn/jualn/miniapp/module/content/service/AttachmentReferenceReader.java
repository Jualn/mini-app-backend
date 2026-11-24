package cn.jualn.miniapp.module.content.service;
@org.springframework.stereotype.Service @lombok.RequiredArgsConstructor
public class AttachmentReferenceReader {
 private final cn.jualn.miniapp.module.content.mapper.AttachmentReferenceMapper mapper;
 public boolean inUse(long attachmentId) {return mapper.count(attachmentId)>0;}
}
