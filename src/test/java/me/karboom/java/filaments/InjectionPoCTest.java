package me.karboom.java.filaments;

import am.ik.yavi.builder.ValidatorBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SQL 注入防御回归测试（纯渲染层断言，无需数据库）。
 *
 * 对应修复的五个注入点：
 *   1. od 排序键：field(String) 裸拼用户可控串 → 标识符校验 + name() 引用
 *   2. jsonb 点路径：'#>> ''{...}''' 字面量未转义 → 路径段白名单 + 段0引用
 *   3. 函数链：函数名/括号参数原样拼入 → 白名单精确匹配 + 参数仅数字
 *   4. INTERSECT：值曾未转义拼进 jsonpath 字面量 → 现走 jsonb_path_exists vars 绑定，值不进 SQL 文本
 *   5. aggregation：字段名仅去反引号 / group 裸拼 / 非白名单函数静默坏 SQL → 校验+报错
 *   6. 条件别名（别名::字段|函数链|操作符）：别名只做参数寻址，字符集白名单校验，
 *      永不进入 SQL；被别名指向的字段/函数链仍走原有标识符与白名单校验。
 */
public class InjectionPoCTest {

    static class User2 {
        public Integer id;
        public String name;
        public String type;
        public java.util.Map<String, Object> extra;
        public java.util.List<Object> tags;
        public java.time.OffsetDateTime createTime;
    }

    static class F extends Filaments<User2> {
        F() { super("filaments_user"); }
        @Override
        public ValidatorBuilder<User2> initValidator() { return new ValidatorBuilder<>(); }
    }

    private final F f = new F();
    private final SelectSpecBuilder builder = new SelectSpecBuilder();

    /** 渲染为折叠空白、小写的可断言字符串 */
    private String render(Map<String, Object> query) {
        var db = org.jooq.impl.DSL.using(org.jooq.SQLDialect.POSTGRES);
        return f.buildSelect(db, query, null).toString()
                .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    @Nested
    @DisplayName("od 排序键")
    class OrderBy {

        @Test
        void rejectsSubqueryPayload() {
            var q = new HashMap<String, Object>();
            q.put("od", "id, (select password_hash from app_user limit 1)");
            assertThatThrownBy(() -> render(q))
                    .isInstanceOf(Error.class)
                    .hasMessageContaining("非法字段名");
        }

        @Test
        void rendersQuotedIdentifier() {
            assertThat(render(Map.of("od", "createTime")))
                    .contains("\"create_time\" asc");
            assertThat(render(Map.of("od", "-id")))
                    .contains("\"id\" desc");
        }
    }

    @Nested
    @DisplayName("jsonb 点路径")
    class JsonbPath {

        @Test
        void rejectsQuoteBreakout() {
            var q = new HashMap<String, Object>();
            q.put("extra.a' || (select password_hash from app_user limit 1) || 'b", "x");
            assertThatThrownBy(() -> render(q))
                    .isInstanceOf(Error.class)
                    .hasMessageContaining("非法字段名");
        }

        @Test
        void rendersValidPath() {
            assertThat(render(Map.of("extra.age", 30)))
                    .contains("\"extra\" #>> '{age}'");
        }
    }

    @Nested
    @DisplayName("函数链")
    class FuncChain {

        @Test
        void rejectsFunctionNotInWhitelist() {
            var q = new HashMap<String, Object>();
            q.put("name|pg_sleep(3)|eq", "x");
            assertThatThrownBy(() -> render(q))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("白名单");
        }

        @Test
        void rejectsNonNumericParam() {
            var q = new HashMap<String, Object>();
            q.put("name|substr((select password_hash from app_user limit 1),1,2)|eq", "x");
            assertThatThrownBy(() -> render(q))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("数字字面量");
        }

        @Test
        void rendersWhitelistedChain() {
            var q = new HashMap<String, Object>();
            q.put("name|substr(1,3)|ne", "abc");
            assertThat(render(q)).contains("substr(\"name\", 1,3)");
        }

        @Test
        void builderRejectsDirectlyToo() {
            assertThatThrownBy(() -> builder.buildFuncChain("name", List.of("pg_sleep(3)")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> builder.buildFuncChain("name", List.of("substr(a,b)")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("条件别名 alias")
    class ConditionAlias {

        @Test
        void aliasNeverReachesSql() {
            var q = new HashMap<String, Object>();
            q.put("lo::extra.age|ge", "18");
            q.put("hi::extra.age|substr(1,2)|le", "60");
            q.put("lg", "(lo,hi)");
            var sql = render(q);
            assertThat(sql)
                    .doesNotContain("::")
                    .contains("\"extra\" #>> '{age}' >= '18'")
                    .contains("substr(\"extra\" #>> '{age}', 1,2) <= '60'");
        }

        @Test
        void rejectsPayloadInAliasPart() {
            var q = new HashMap<String, Object>();
            q.put("na me)::; drop::name", "x");
            q.put("lg", "(na me)");
            assertThatThrownBy(() -> render(q)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsUnknownAliasReference() {
            var q = new HashMap<String, Object>();
            q.put("name", "John");
            q.put("lg", "(secret_alias)");
            assertThatThrownBy(() -> render(q))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("secret_alias");
        }

        @Test
        void aliasCannotBypassFieldNameValidation() {
            var q = new HashMap<String, Object>();
            q.put("a::(select password_hash from app_user limit 1)", "x");
            q.put("lg", "(a)");
            assertThatThrownBy(() -> render(q))
                    .isInstanceOf(Error.class)
                    .hasMessageContaining("非法字段名");
        }
    }

    @Nested
    @DisplayName("INTERSECT 值绑定")
    class Intersect {

        @Test
        void valuesAreBoundNotInlined() {
            var q = new HashMap<String, Object>();
            q.put("tags|intersect", List.of("a') or 1=1 --", "x\") or true"));
            var sql = render(q);
            // 谓词只引用 jsonpath 变量名，值全部走绑定参数
            assertThat(sql).contains("jsonb_path_exists")
                    .contains("@ == $q0 || @ == $q1")
                    .contains("jsonb_build_object");
            // 任何值的原文都不允许出现在 SQL 文本里
            assertThat(sql).doesNotContain("1=1").doesNotContain("or true");
        }
    }

    @Nested
    @DisplayName("aggregation 聚合")
    class Aggregation {

        private String renderAgg(Map<String, Object> target, List<String> group) {
            var db = org.jooq.impl.DSL.using(org.jooq.SQLDialect.POSTGRES);
            return f.buildAggregation(db, target, Map.of(), group, null).toString()
                    .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        }

        @Test
        void rejectsFieldNamePayload() {
            assertThatThrownBy(() -> renderAgg(Map.of("count", "id) from admin -- "), List.of()))
                    .isInstanceOf(Error.class)
                    .hasMessageContaining("非法字段名");
        }

        // "median" 不在任何数据库预设白名单里；曾用作本例的 "sum" 现已收录（PG_FUNCS）
        @Test
        void rejectsUnsupportedFunc() {
            assertThatThrownBy(() -> renderAgg(Map.of("median", "id"), List.of()))
                    .isInstanceOf(Error.class)
                    .hasMessageContaining("不支持的聚合函数");
        }

        @Test
        void rejectsGroupPayload() {
            assertThatThrownBy(() -> renderAgg(Map.of("count", "id"), List.of("type; drop table x --")))
                    .isInstanceOf(Error.class)
                    .hasMessageContaining("非法字段名");
        }

        @Test
        void rendersValidAggregation() {
            var sql = renderAgg(Map.of("count", "id"), List.of("type"));
            assertThat(sql).contains("count(\"id\")").contains("group by \"type\"");
        }
    }
}
