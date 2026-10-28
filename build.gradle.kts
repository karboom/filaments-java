import com.vanniktech.maven.publish.DeploymentValidation

plugins {
    id("java-library")
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "io.github.karboom"
version = "2.0.2"

repositories {
    mavenCentral()
}

dependencies {
    // 出现在公开 API 签名中，下游编译需要 -> api
    api("org.jooq:jooq:3.21.9")
    api("tools.jackson.core:jackson-databind:3.2.4")
    api("am.ik.yavi:yavi:0.17.2")

    implementation("cn.hutool:hutool-all:5.8.48")
    // 内部字段访问，不出现在公开签名
    implementation("com.esotericsoftware:reflectasm:1.11.9")
    implementation("net.postgis:postgis-jdbc:2025.1.1")

    compileOnly("org.projectlombok:lombok:1.18.48")
    annotationProcessor("org.projectlombok:lombok:1.18.48")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.postgresql:postgresql:42.7.14")

    testCompileOnly("org.projectlombok:lombok:1.18.48")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.48")
}


mavenPublishing {
    // 0.37 起 SonatypeHost 移除，仅保留 Central Portal 链路
    // 参数：automaticRelease, 发布后校验级别（NONE/VALIDATED/PUBLISHED）
    publishToMavenCentral(true, DeploymentValidation.VALIDATED)

//    configure(JavaLibrary(
//        // configures the -javadoc artifact, possible values:
//        // - `JavadocJar.None()` don't publish this artifact
//        // - `JavadocJar.Empty()` publish an emprt jar
//        // - `JavadocJar.Javadoc()` to publish standard javadocs
//        javadocJar = JavadocJar.Javadoc(),
//        // whether to publish a sources jar
//        sourcesJar = true,
//    ))

    signAllPublications()

    coordinates("io.github.karboom", "filaments-java", version.toString())

    pom {
        name.set("filaments")
        description.set("一个轻量级的 Java ORM 库，提供了一组语法可以将查询字符串直接转换为 SQL 查询，并且提供了一组 NLP 友好的数据操作接口")
        inceptionYear.set("2024")
        url.set("https://github.com/karboom/filaments-java")
        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("karboom")
                name.set("karboom")
                url.set("https://github.com/karboom/")
            }
        }
        scm {
            url.set("https://github.com/karboom/filaments-java")
            connection.set("scm:git:git://github.com/karboom/filaments-java.git")
            developerConnection.set("scm:git:ssh://git@github.com/karboom/filaments-java.git")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}