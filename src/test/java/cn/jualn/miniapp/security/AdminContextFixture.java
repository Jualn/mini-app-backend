package cn.jualn.miniapp.security;

/** Thread-local identity for unit tests only. No Redis or authentication claim. */
public final class AdminContextFixture {
    private AdminContextFixture() {}
    public static void open(long user) {
        var storage=org.mockito.Mockito.mock(cn.dev33.satoken.context.model.SaStorage.class);
        java.util.Map<String,Object> values=new java.util.HashMap<>();
        org.mockito.Mockito.when(storage.get(org.mockito.ArgumentMatchers.anyString())).thenAnswer(i->values.get(i.getArgument(0)));
        org.mockito.Mockito.when(storage.set(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any())).thenAnswer(i->{values.put(i.getArgument(0),i.getArgument(1));return storage;});
        cn.dev33.satoken.SaManager.getSaTokenContext().setContext(org.mockito.Mockito.mock(cn.dev33.satoken.context.model.SaRequest.class),org.mockito.Mockito.mock(cn.dev33.satoken.context.model.SaResponse.class),storage);
        cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.switchTo(user);
    }
    public static void close() {cn.dev33.satoken.SaManager.getSaTokenContext().clearContext();}
}
