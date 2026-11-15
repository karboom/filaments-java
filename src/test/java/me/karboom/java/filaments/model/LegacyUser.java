package me.karboom.java.filaments.model;

import java.util.Date;

/**
 * 反例模型：java.util.Date 时间字段（读侧应被响亮拒绝）
 */
public class LegacyUser {
    public String id;
    public Date createTime;
}
