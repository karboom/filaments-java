package me.karboom.java.filaments.model;

import java.time.OffsetDateTime;

/**
 * 驼峰列模型：对应 filaments_camel 表（config.naming = camel）
 */
public class CamelUser {
    public String id;
    public String name;
    public String wechatId;
    public OffsetDateTime createTime;
}
