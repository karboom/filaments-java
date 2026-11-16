import com.vanniktech.maven.publish.DeploymentValidation

plugins {
    id("java-library")
    id("checkstyle")
    id("jacoco")
    id("com.vanniktech.maven.publish") version "0.37.0"
    id ("io.freefair.lombok") version "9.5.0"
}

group = "io.github.karboom"
version = "2.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // 出现在公开 API 签名中，下游编译需要 -> api
    api("org.jooq:jooq:3.21.9")
    api("tools.jackson.core:jackson-databind:3.2.4")
    api("am.ik.yavi:yavi:0.17.2")

    implementation("cn.hutool:hutool-all:5.8.48")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.postgresql:postgresql:42.7.14")

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
    finalizedBy(tasks.jacocoTestReport)
}

jacoco {
    toolVersion = "0.8.16"
}

tasks.jacocoTestReport {
    // 不加 dependsOn(test)：作为 test 的 finalizer，测试失败时报告照样产出
    reports {
        html.required = true
        xml.required = true
        csv.required = false
    }
}

tasks.jacocoTestCoverageVerification {
    // 阈值基于实测（line 89%/branch 80%）下调留余量；聚合投影泄漏 bug 修复后可上调
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.85".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.75".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

checkstyle {
    toolVersion = "14.4.0"
    configDirectory = file("config/checkstyle")
}

tasks.withType<Checkstyle>().configureEach {
    isShowViolations = true
    maxWarnings = 0
    maxErrors = 0
    configProperties = mapOf(
        "checkstyle.suppressions.file" to file("config/checkstyle/suppressions.xml").absolutePath
    )
}