package cn.jualn.miniapp.module.media.converter;

import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.vo.AttachmentVO;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class AttachmentConverter {
    public AttachmentVO toVO(MediaAttachmentBO value) {
        return new AttachmentVO(value.getId().toString(), value.getKind() != null ? value.getKind() :
                switch (Objects.requireNonNull(value.getType(), "attachment type")) {
                    case IMAGE -> "IMAGE";
                    case PDF -> "PDF";
                    case WORD -> "WORD";
                    case URL -> "LINK";
                }, value.getOriginalName() == null ? "attachment-" + value.getId() : value.getOriginalName(),
                value.getUrl());
    }
}
