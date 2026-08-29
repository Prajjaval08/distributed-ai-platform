package com.distributedai.api.config;

public class DataSourceContextHolder {

    public enum DataSourceType { PRIMARY, REPLICA }

    private static final ThreadLocal<DataSourceType> CONTEXT = ThreadLocal.withInitial(() -> DataSourceType.PRIMARY);

    public static void useReplica() {
        CONTEXT.set(DataSourceType.REPLICA);
    }

    public static void usePrimary() {
        CONTEXT.set(DataSourceType.PRIMARY);
    }

    public static DataSourceType getCurrent() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}