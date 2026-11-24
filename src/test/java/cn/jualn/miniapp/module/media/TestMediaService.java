package cn.jualn.miniapp.module.media;
/** Test doubles may supply multiple capabilities; production implementations remain separate. */
public interface TestMediaService extends cn.jualn.miniapp.module.media.service.MediaService,
        cn.jualn.miniapp.module.media.service.MediaQueries,cn.jualn.miniapp.module.media.service.MediaAccess {}
