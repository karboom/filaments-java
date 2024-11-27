import com.vanniktech.maven.publish.SonatypeHost
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar



plugins {
    id("java")
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "io.github.karboom"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jooq:jooq:3.19.15")
    implementation("cn.hutool:hutool-all:5.8.33")
    implementation("com.esotericsoftware:reflectasm:1.11.9")
    implementation("am.ik.yavi:yavi:0.14.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("net.postgis:postgis-jdbc:2.5.1")

    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.postgresql:postgresql:42.7.4")

    testCompileOnly("org.projectlombok:lombok:1.18.34")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.34")
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

    coordinates("io.github.karboom", "filaments-java", "1.0.0")

    pom {
        name.set("My Library")
        description.set("A description of what my library does.")
        inceptionYear.set("2020")
        url.set("https://github.com/username/mylibrary/")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
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
            url.set("https://github.com/username/mylibrary/")
            connection.set("scm:git:git://github.com/username/mylibrary.git")
            developerConnection.set("scm:git:ssh://git@github.com/username/mylibrary.git")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}