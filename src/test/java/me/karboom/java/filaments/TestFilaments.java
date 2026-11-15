package me.karboom.java.filaments;

import am.ik.yavi.builder.ValidatorBuilder;
import me.karboom.java.filaments.model.Address;
import me.karboom.java.filaments.model.User;
import me.karboom.java.filaments.model.UserEnum;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectQuery;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * 测试用 User 模型 Filaments：
 *   1. before / after / validator 钩子用函数接口注入（不做 Class + override）；
 *   2. 暴露 buildSelect / buildAggregation 的公开包装，供无连接渲染断言。
 */
public class TestFilaments extends Filaments<User> {

    public Consumer<User> beforeHook = data -> {};
    public UnaryOperator<User> afterHook = data -> data;
    public Supplier<ValidatorBuilder<User>> validator = ValidatorBuilder::new;

    public TestFilaments(String table) {
        super(table);
    }

    public TestFilaments(String table, String pk) {
        super(table, pk);
    }

    public TestFilaments(String table, FilamentsConfig config) {
        super(table, config);
    }

    public TestFilaments(String table, String pk, FilamentsConfig config) {
        super(table, pk, config);
    }

    @Override
    public User before(User data) {
        beforeHook.accept(data);
        return data;
    }

    @Override
    public User after(User data) {
        return afterHook.apply(data);
    }

    @Override
    public ValidatorBuilder<User> initValidator() {
        return validator.get();
    }

    /** 渲染级构建（不连库） */
    public SelectQuery<Record> build(DSLContext db, Map<String, Object> query) {
        return buildSelect(db, query, null);
    }

    /** 聚合渲染级构建（不连库） */
    public SelectQuery<Record> buildAgg(DSLContext db, Map<String, Object> target,
                                        Map<String, Object> query, List<String> group) {
        return buildAggregation(db, target, query, group, null);
    }

    /** 字段齐全的种子数据：jsonb / 枚举 / BigDecimal / 时间全覆盖 */
    public static User seed(String id) {
        var user = new User();
        user.id = id;
        user.name = "user-" + id;
        user.age = 20 + Integer.parseInt(id);
        user.wechatId = "wx_" + id;
        user.type = UserEnum.A;
        user.tags = List.of("a", "b");
        user.address = new Address();
        user.address.city = "sh";
        user.address.age = 10 + Integer.parseInt(id);
        user.profile = Map.of("nick", "nick-" + id);
        user.balance = new BigDecimal("12.3456");
        user.createTime = OffsetDateTime.parse("2026-01-01T12:00:00+08:00");
        return user;
    }
}
