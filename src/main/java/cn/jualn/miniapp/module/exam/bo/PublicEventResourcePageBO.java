package cn.jualn.miniapp.module.exam.bo;

import java.util.List;

/** Public PublicEvent cursor page independent of the legacy numeric cursor response. */
public record PublicEventResourcePageBO(List<ExamDetailBO> items, String nextCursor) {}
