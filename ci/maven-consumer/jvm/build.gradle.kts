import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
application {
    mainClass.set("com.kausio.prism.releaseconsumer.MainKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

val prismVersion = providers.gradleProperty("prismVersion").get()
dependencies {
    implementation("com.kaus-io:prism-motion-core:$prismVersion")
    implementation("com.kaus-io:prism-motion-compose:$prismVersion")
    implementation("com.kaus-io:prism-motion-engine-kap:$prismVersion")
    implementation("com.kaus-io:prism-motion-engine-vap:$prismVersion")
    implementation("com.kaus-io:prism-motion-format:$prismVersion")
    implementation("com.kaus-io:prism-motion-ktx:$prismVersion")
    implementation("com.kaus-io:prism-motion-vap:$prismVersion")
    implementation("com.kaus-io:prism-motion-probe:$prismVersion")
    implementation("com.kaus-io:prism-motion-utils:$prismVersion")
}
