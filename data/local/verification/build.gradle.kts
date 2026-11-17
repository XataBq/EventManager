// Verification only: compile the exact production Room schema/DAO/store sources against Room JVM.
// Not a runtime app module and not an in-memory imitation of SQLite.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    sourceSets.main { kotlin.srcDir("../src/main/kotlin"); kotlin.exclude("**/AndroidDraftStoreFactory.kt") }
    sourceSets.test { kotlin.srcDir("../../remote/src/main/kotlin");kotlin.srcDir("../../remote/src/test/kotlin/ru/eventpass/data/remote/support") }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":core:domain"))
    implementation(libs.room.runtime)
    implementation(libs.sqlite.bundled)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    ksp(libs.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp)
    testImplementation(libs.mockwebserver)
}
