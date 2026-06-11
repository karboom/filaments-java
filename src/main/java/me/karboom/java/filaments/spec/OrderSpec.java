package me.karboom.java.filaments.spec;

import lombok.Builder;
import lombok.Data;

/**
 * 排序规则
 */
@Data
@Builder
public class OrderSpec {
    public static final String ASC = "ASC";
    public static final String DESC = "DESC";

    private String field;
    private String direction;
}
