package me.karboom.java.filaments;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
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

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RoleGrant{
        public Boolean edit;
    }


    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Role {
        public String name;
        public RoleGrant grant;
    }

    public List<Role> roles;
    public List<String> actionIds;
    public String name;
    public OffsetDateTime createTime;
    public String wechatId;
    public String type;
    public String id;
    public Extra extra;
    public String location;
}
