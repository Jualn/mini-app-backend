package cn.jualn.miniapp.module.post.bo;

import java.util.List;
import java.util.Set;

public record PostDictionarySaveBO(String code, String name, String status, Integer displayOrder,
                                   List<String> postTypes, Set<String> fields) {
}
