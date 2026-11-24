package cn.jualn.miniapp.module.documentimport;

public interface DocumentImportAiGateway {
    String generate(String prompt);
    default String modelName() { return "unknown"; }
}
