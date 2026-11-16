// 临时探针：验证通配投影下"首行按元数据建列策略"是否可用。用完即删，不含业务断言。
package me.karboom.java.filaments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.Map;

import static org.jooq.impl.DSL.asterisk;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

@Timeout(30)
class ScratchMetaProbeTest {

    @Test
    void probe() {
        TestDb.initTables();
        TestDb.truncateAll();
        var db = TestDb.db();
        var f = new TestFilaments(TestDb.TABLE);
        f.create(db, java.util.stream.IntStream.range(0, 3)
                .mapToObj(i -> TestFilaments.seed(String.valueOf(i))).toList());

        // 1) 通配投影 + doQuery：列策略若从元数据建，字段应有值；从 getSelect() 建则全 null
        var q = db.selectQuery();
        q.addFrom(table(name(TestDb.TABLE)));
        q.addSelect(asterisk());
        var mapped = f.doQuery(q, false);
        System.out.println("PROBE-STAR rows=" + mapped.size()
                + " id=" + mapped.getFirst().id + " name=" + mapped.getFirst().name
                + " tags=" + mapped.getFirst().tags + " createTime=" + mapped.getFirst().createTime
                + " balance=" + mapped.getFirst().balance + " type=" + mapped.getFirst().type);

        // 2) 空结果集：没有首行，策略不建，返回空列表即可
        var empty = db.selectQuery();
        empty.addFrom(table(name(TestDb.TABLE)));
        empty.addSelect(asterisk());
        empty.addConditions(field(name("id")).eq("不存在的id"));
        System.out.println("PROBE-EMPTY " + f.doQuery(empty, false));

        // 3) 现有默认投影（模型列）与 rt 通道仍要正常
        System.out.println("PROBE-MODEL " + f.get(db, Map.of()).getFirst().tags);
        System.out.println("PROBE-RT " + f.get(db, Map.of("rt", "id,tags,create_time")).getFirst().tags);

        // 4) 重名列（子查询包裹）元数据表现
        var dup = db.selectQuery();
        dup.addFrom(table(name(TestDb.TABLE)));
        dup.addSelect(asterisk());
        dup.addSelect(field(name("id")));
        System.out.println("PROBE-DUP-SQL " + dup.getSQL());
        System.out.println("PROBE-DUP-FIELDS " + Arrays.stream(dup.fetch().fields())
                .map(x -> x.getName()).toList());
    }
}
