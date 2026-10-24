import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("java-library")
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "io.github.karboom"
version = "2.0.1"

repositories {
    mavenCentral()
}

dependencies {
    // 出现在公开 API 签名中，下游编译需要 -> api
    api("org.jooq:jooq:3.19.15")
    api("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    api("am.ik.yavi:yavi:0.14.1")

    implementation("cn.hutool:hutool-all:5.8.33")
    // 仅运行时使用（静态初始化 java.time 支持），不暴露给下游编译
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")
    // 内部字段访问，不出现在公开签名
    implementation("com.esotericsoftware:reflectasm:1.11.9")
    implementation("net.postgis:postgis-jdbc:2.5.1")

    compileOnly("org.projectlombok:lombok:1.18.36")
    annotationProcessor("org.projectlombok:lombok:1.18.36")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.postgresql:postgresql:42.7.4")

    testCompileOnly("org.projectlombok:lombok:1.18.36")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.36")
}


mavenPublishing {
//    publishToMavenCentral(SonatypeHost.DEFAULT)
//    // or when publishing to https://s01.oss.sonatype.org
//    publishToMavenCentral(SonatypeHost.S01)
//    // or when publishing to https://central.sonatype.com/
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)

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
        description.set("")
        inceptionYear.set("2024")
        url.set("https://github.com/karboom/filaments-java")
        licenses {
            license {
                name.set("MIT License")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
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