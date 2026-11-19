package cn.jualn.miniapp.module.learningtask.service;

/** Synchronous candidate check outside all database write transactions. */
public interface LearningTaskTextSafety {
    void check(long actor, String text);
}
