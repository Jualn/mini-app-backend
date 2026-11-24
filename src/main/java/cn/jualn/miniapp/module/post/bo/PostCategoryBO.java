package cn.jualn.miniapp.module.post.bo;

import java.util.List;

public record PostCategoryBO(Long id, String code, String name, List<String> postTypes, String status,
                             Integer displayOrder) {
}
