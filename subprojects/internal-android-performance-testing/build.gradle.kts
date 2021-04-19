plugins {
    id("gradlebuild.internal.java")
    application
}

repositories {
    google()
}

dependencies {
    implementation(project(":base-services"))
    implementation(project(":tooling-api"))
    api("com.android.tools.build:gradle:3.0.0") {
        // unify the trove4j version we're using
        exclude(group = libs.trove4j.substringBefore(":"), module = libs.trove4j.substringAfter(":"))
    }
    implementation(libs.trove4j)
}

application {
    mainClass.set("org.gradle.performance.android.Main")
    applicationName = "android-test-app"
}

listOf(tasks.distZip, tasks.distTar).forEach {
    it { archiveBaseName.set("android-test-app") }
}

tasks.register("buildDists") {
    dependsOn(tasks.distZip)
}
