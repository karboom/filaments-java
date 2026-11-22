package me.karboom.java.filaments.model;

import org.jooq.JSON;
import org.jooq.JSONB;

/**
 * 反例模型：jOOQ 自有 JSON 类型声明字段（构造期应直接拒绝）
 */
public class BadJooqJsonUser {
    public String id;
    public JSONB jsonbField;
    public JSON jsonField;
}
