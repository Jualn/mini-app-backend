package cn.jualn.miniapp.module.publicevent.bo;

import java.util.List;

/** Public PublicEvent cursor page independent of the legacy numeric cursor response. */
public record PublicEventResourcePageBO(List<PublicEventDetailBO> items, String nextCursor) {}
