package cn.jualn.miniapp.module.media.converter;

import cn.jualn.miniapp.common.enums.MediaType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSimpleBO;
import cn.jualn.miniapp.module.media.dto.request.MediaAttachmentSaveRequest;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSaveBO;
import cn.jualn.miniapp.module.media.entity.MediaAttachment;
import cn.jualn.miniapp.common.mapper.EnumConverter;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring", uses = EnumConverter.class)
public interface MediaConverter {

    MediaAttachmentSaveBO toSaveDTO(MediaAttachmentSaveRequest request);

    List<MediaAttachmentBO> toBOList(List<MediaAttachment> entities);
    MediaAttachmentBO toBO(MediaAttachment entity);

    List<MediaAttachmentSimpleBO> toSimpleBOList(List<MediaAttachment> entities);

    // 会对入参进行扁平化处理，item中的字段跟MediaAttachment字段有对应也能映射(仅限一级其余通过Mapping)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    MediaAttachment toMediaAttachment(AttachmentItemBO item, Integer targetType, Long targetId);

    // Integer<->Enum mappings delegated to EnumConverter

    default List<MediaAttachment> toMediaAttachmentList(MediaAttachmentSaveBO saveDTO) {
        if (saveDTO == null || saveDTO.getAttachments() == null) {
            return null;
        }

        List<MediaAttachment> mediaAttachments = saveDTO.getAttachments().stream()
                .map(item -> toMediaAttachment(item, saveDTO.getTargetType().getCode(), saveDTO.getTargetId()))
                .collect(Collectors.toList());

        // 如果order为空，按照入参顺序设置
        for (int i = 0; i < mediaAttachments.size(); i++) {
            MediaAttachment mediaAttachment = mediaAttachments.get(i);
            if (mediaAttachment.getKind() == null && mediaAttachment.getType() != null) {
                mediaAttachment.setKind(switch (MediaType.fromCode(mediaAttachment.getType())) {
                    case URL -> "LINK";
                    case IMAGE -> "IMAGE";
                    case PDF -> "PDF";
                    case WORD -> "WORD";
                });
            }
            mediaAttachment.setRegistered(Boolean.TRUE);
            if (mediaAttachment.getSortOrder() == null) {
                mediaAttachment.setSortOrder(i + 1);  // 设置order，从1开始
            }
        }

        return mediaAttachments;
    }

}
