-- =====================================================================
-- filaments 集成测试基础表（start_test_env.sh 初始化空库时导入 user schema）
-- 测试代码不再 DROP/CREATE 表结构，仅清行；种子数据由 Filaments.create 自举写入
-- =====================================================================

-- ORM 主测试表：对应 src/test/.../User.java
--   roles/action_ids: JSONB 数组列（验证 handleJson 与 #>> 路径查询）
--   extra:            JSONB 对象列（验证 nest 校验与嵌套解析）
--   create_time:      TIMESTAMPTZ（OffsetDateTime 模型）
--   注意：不建 geometry 列（测试镜像为纯 postgres，无 postgis）
CREATE TABLE IF NOT EXISTS "filaments_user" (
    id BIGINT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    wechat_id varchar(20) NOT NULL,
    type varchar(1) not null,
    roles JSONB DEFAULT '[]'::jsonb,
    extra JSONB not null DEFAULT '{}'::jsonb,
    create_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    location varchar(50) NOT NULL,
    action_ids JSONB DEFAULT '[]'::jsonb
);

-- 自定义主键（pk=uid）全链路测试表：对应测试内 Mini 模型
CREATE TABLE IF NOT EXISTS "user_mini_pk" (
    uid BIGINT PRIMARY KEY,
    name VARCHAR(255) NOT NULL
);

-- 驼峰列名测试表：对应 FilamentsConfigTest（columnNaming=CAMEL）
CREATE TABLE IF NOT EXISTS "filaments_camel" (
    "userId" BIGINT PRIMARY KEY,
    "wechatId" VARCHAR(20) NOT NULL,
    "createTime" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
