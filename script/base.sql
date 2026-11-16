-- =====================================================================
-- filaments 集成测试建表脚本：表结构唯一真相
--   1) TestDb.initTables() 读取本文件并在连接上逐条执行（幂等，可重复运行测试）
--   2) start_test_env.sh 首次拉起空容器时导入本文件，便于 psql 手工查看
--   所有对象显式限定 filaments_test schema，不依赖连接串里的 search_path。
--   新增/修改测试模型字段只需改这里，Java 侧不再另存 DDL。
--   约束：注释里不要出现分号，TestDb 按分号切分语句。
-- =====================================================================

create schema if not exists filaments_test;

-- 主测试表，对应 model/User.java：字符串主键 + snake 列名 + jsonb 结构化列 + timestamptz
create table if not exists filaments_test.filaments_user (
    id          varchar(64) primary key,
    name        varchar(64),
    age         int4,
    wechat_id   varchar(64),
    type        varchar(16),
    tags        jsonb,
    address     jsonb,
    profile     jsonb,
    balance     numeric(18, 4),
    create_time timestamptz
);

-- 驼峰列名表，对应 model/CamelUser.java（FilamentsConfig.naming = camel）
create table if not exists filaments_test.filaments_camel (
    id           varchar(64) primary key,
    name         varchar(64),
    "wechatId"   varchar(64),
    "createTime" timestamptz
);

-- 遗留时间列表，对应 model/LegacyUser.java（java.util.Date 读路径应报错）
create table if not exists filaments_test.filaments_legacy (
    id          varchar(64) primary key,
    create_time timestamptz
);
