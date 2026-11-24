package cn.jualn.miniapp.module.interact.vo;
/** Counts are JSON numbers; the global Long-as-resource-ID serializer must not stringify them. */
public final class InteractionCountSerializer extends com.fasterxml.jackson.databind.ser.std.NumberSerializer {
    public InteractionCountSerializer() {super(Long.class);}
}
