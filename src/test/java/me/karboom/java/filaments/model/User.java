package me.karboom.java.filaments.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 主测试模型：对应 filaments_user 表（snake 列名 + jsonb 列 + timestamptz）
 */
public class User {

    public String id;
    public String name;
    public Integer age;
    public String wechatId;
    public UserEnum type;
    public List<String> tags;
    public Address address;
    public Map<String, Object> profile;
    public BigDecimal balance;
    public OffsetDateTime createTime;

    // getter：yavi 约束需要方法引用（字段本身仍由 Filaments 反射读写）
    public String getName() {
        return name;
    }
}
