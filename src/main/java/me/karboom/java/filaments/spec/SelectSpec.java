package me.karboom.java.filaments.spec;

import lombok.Data;

import java.util.List;

@Data
public class SelectSpec {
    private List<String> selectFields;      // 返回字段，null 表示 SELECT *
    private LogicNode where;                // WHERE 条件树
    private List<OrderSpec> orderBy;        // ORDER BY
    private Integer limit;
    private Integer offset;
}
