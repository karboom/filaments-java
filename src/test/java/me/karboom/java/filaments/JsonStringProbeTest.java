package me.karboom.java.filaments;

import org.junit.jupiter.api.Test;
import org.jooq.JSON;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;

/**
 * 探针：能否不持有 org.jooq.JSON/JSONB，取值时用 String.class 让 jOOQ 内建转换器拆出原始文本。
 * 若成立，doQuery 的 JSON 分支可退化为 record.get(i, String.class) + 单次 readValue。
 */
public class JsonStringProbeTest {

    @Test
    void getAsStringUnwrapsJsonbAndJsonAndKeepsVarchar() {
        var ctx = DSL.using(SQLDialect.POSTGRES);
        var fj = field("j", JSONB.class);
        var fq = field("q", JSON.class);
        var ft = field("t", String.class);
        var rec = ctx.newRecord(fj, fq, ft);
        rec.set(fj, JSONB.valueOf("{\"a\":1}"));
        rec.set(fq, JSON.valueOf("{\"a\":2}"));
        rec.set(ft, "{\"a\":3}");

        assertThat(rec.get(0, String.class)).isEqualTo("{\"a\":1}");
        assertThat(rec.get(1, String.class)).isEqualTo("{\"a\":2}");
        assertThat(rec.get(2, String.class)).isEqualTo("{\"a\":3}");
    }

    @Test
    void nullStaysNull() {
        var ctx = DSL.using(SQLDialect.POSTGRES);
        var rec = ctx.newRecord(field("j", JSONB.class));
        assertThat(rec.get(0, String.class)).isNull();
    }
}
