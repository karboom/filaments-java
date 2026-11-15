package me.karboom.java.filaments.model;

/**
 * 反例模型：Object 声明字段（构造期应直接拒绝）
 */
public class BadObjectUser {
    public String id;
    public Object extra;
}
