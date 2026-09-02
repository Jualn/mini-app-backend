package cn.jualn.miniapp.module.media.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class AdminMediaUploadCredentialRequest {

    @NotEmpty(message = "fileNames 不能为空")
    @Size(max = 9, message = "最多上传9个附件")
    private List<@NotBlank(message = "fileName 不能为空")
            @Size(max = 255, message = "fileName 长度不能超过255") String> fileNames;
}
