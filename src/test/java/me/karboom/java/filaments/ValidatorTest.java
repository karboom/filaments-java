package me.karboom.java.filaments;

import am.ik.yavi.builder.ValidatorBuilder;
import me.karboom.java.filaments.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * defaultValidator（不连库）：根字段为 null 的约束整条跳过；
 * 只用公共 API 重建 Validator，绝不改动传入的 builder。
 */
@Timeout(5)
class ValidatorTest {

    TestFilaments f;

    @BeforeEach
    void setUp() {
        f = new TestFilaments("whatever");
    }

    @Nested
    class NullRootSkip {

        @Test
        void nullRootSkipsConstraint() {
            var builder = new ValidatorBuilder<User>();
            builder._string(u -> u.address.city, "address.city", c -> c.notBlank());

            var user = TestFilaments.seed("1");
            user.address = null;

            // 约束若未被跳过，toValue 会在 null 的 address 上取 city 直接 NPE
            var result = f.defaultValidator(builder, user).validate(user);
            assertThat(result.isValid()).isTrue();
        }

        @Test
        void sharedBuilderNotPolluted() {
            var builder = new ValidatorBuilder<User>();
            builder._string(u -> u.address.city, "address.city", c -> c.notBlank());

            var noAddr = TestFilaments.seed("1");
            noAddr.address = null;
            f.defaultValidator(builder, noAddr).validate(noAddr);

            // 旧实现 removeIf 作用于传入 builder 的谓词表，上一行会把约束永久删掉；
            // 这里同一 builder 再校验 city 为空的行，约束必须仍在且触发违规
            var bad = TestFilaments.seed("2");
            bad.address.city = "";
            var result = f.defaultValidator(builder, bad).validate(bad);
            assertThat(result.isValid()).isFalse();
        }

        @Test
        void flatNullFieldStillSkipped() {
            // 钉住既有语义：根值 null 时整条约束跳过（notBlank 也不会报违规）
            var builder = new ValidatorBuilder<User>();
            builder._string(u -> u.name, "name", c -> c.notBlank());

            var user = TestFilaments.seed("1");
            user.name = null;
            var result = f.defaultValidator(builder, user).validate(user);
            assertThat(result.isValid()).isTrue();
        }

        @Test
        void failFastKept() {
            var builder = new ValidatorBuilder<User>();
            builder._string(u -> u.name, "name", c -> c.notBlank().greaterThanOrEqual(5));

            var user = TestFilaments.seed("1");
            user.name = ""; // 两条约束都违规：failFast 只应报第一条
            var result = f.defaultValidator(builder, user).validate(user);
            // failFast(true) 下只报第一条违规
            assertThat(result.size()).isEqualTo(1);
        }
    }
}
