package me.karboom.java.filaments;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;

/**
 * 性能探针：按规范不设断言，只输出耗时（真库，串行）。
 */
@Timeout(120)
class PerformanceTest {

    static final int ROWS = 2000;

    @BeforeAll
    static void initDb() {
        TestDb.initTables();
    }

    @Test
    void measureCreateQueryAggregate() {
        TestDb.truncateAll();
        var db = TestDb.db();
        var f = new TestFilaments(TestDb.TABLE);

        var users = java.util.stream.IntStream.range(0, ROWS)
                .mapToObj(i -> TestFilaments.seed(String.valueOf(i % 10)))
                .toList();
        // id 会撞主键，逐行改唯一 id
        java.util.stream.IntStream.range(0, ROWS).forEach(i -> users.get(i).id = "p" + i);

        long t0 = System.nanoTime();
        f.create(db, users);
        long tCreate = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("[perf] create %d rows: %d ms%n", ROWS, tCreate);

        long t1 = System.nanoTime();
        var rows = f.get(db, Map.of("rt", "id,name,age,tags,address,profile,createTime"));
        long tGet = (System.nanoTime() - t1) / 1_000_000;
        System.out.printf("[perf] get %d rows(全类型): %d ms%n", rows.size(), tGet);

        long t2 = System.nanoTime();
        var agg = f.aggregation(db, Map.of("count", "id", "avg", "age"), Map.of(), java.util.List.of("type"), null);
        long tAgg = (System.nanoTime() - t2) / 1_000_000;
        System.out.printf("[perf] aggregation group by: %d ms -> %s%n", tAgg, agg);
    }
}
