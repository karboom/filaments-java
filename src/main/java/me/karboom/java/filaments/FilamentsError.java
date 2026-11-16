package me.karboom.java.filaments;

/**
 * 库统一异常：参数非法、防注入拒绝、校验失败等所有主动抛出均用它。
 */
public class FilamentsError extends RuntimeException {
    public FilamentsError(String message) {
        super(message);
    }
}
