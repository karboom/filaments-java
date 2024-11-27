package me.karboom.java;

import cn.hutool.core.date.DateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class User {
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Extra {
        public Number age;
        public String email;
        public Number totalCount;
    }
    public List<String> roles;
    public String name;
    public DateTime createTime;
    public String wechatId;
    public String type;
    public String id;
    public Extra extra;
    public String location;
}
