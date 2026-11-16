package me.karboom.java.filaments;

/**
 * 库统一异常：参数非法、防注入拒绝、校验失败等所有主动抛出均用它。
 */
public class FilamentsError extends RuntimeException {
    public FilamentsError(String message) {
        super(message);
    }

    /**
     * 包装底层异常（Jackson 反序列化、反射赋值等数据相关失败）时使用。
     * <p>message 只写底层说不出的业务坐标（行号、列名、目标类型），底层原因交给 cause，
     * 不在 message 里重复 {@code e.getMessage()}——完整栈由日志的 Caused by 段呈现。
     */
    public FilamentsError(String message, Throwable cause) {
        super(message, cause);
    }
}
