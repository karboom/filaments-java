package me.karboom.java.filaments;

import java.util.List;

public class PageResult<T> {
    /** 总条数：PG count(*) 是 bigint，Long 才能原样容纳 */
    public Long total;
    public List<T> list;
}
