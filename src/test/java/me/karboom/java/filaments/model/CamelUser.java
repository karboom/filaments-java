package me.karboom.java.filaments.model;

import java.time.OffsetDateTime;

/**
 * 驼峰列模型：对应 filaments_camel 表（该表的实例用 naming 为 camel 的配置）
 */
public class CamelUser {
    public String id;
    public String name;
    public String wechatId;
    public OffsetDateTime createTime;
}
