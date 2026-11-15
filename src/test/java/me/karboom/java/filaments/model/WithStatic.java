package me.karboom.java.filaments.model;

import java.util.List;

/**
 * 仅用于构造期字段扫描验证：含 static 字段与 Object 之外的正常字段。
 * 注意：本模型只用于反射检查 jsonFields/modelFields，不参与 create/update（见报告：create 不跳过 static）。
 */
public class WithStatic {
    public static final String MODEL_TAG = "static-should-be-skipped";
    public String id;
    public String name;
    public List<String> tags;
}
