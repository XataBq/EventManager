plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "ru.eventpass.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "ru.eventpass.android"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-t01"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "backend"
    productFlavors {
        create("demo") {
            dimension = "backend"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true }
}

// No release/production application exists until a real backend is integrated.
androidComponents {
    beforeVariants { variant -> variant.enable = variant.buildType == "debug" }
}
val enabledVariants = mutableSetOf<String>()
androidComponents.onVariants { enabledVariants += it.name }
tasks.register("verifyDemoIsolation") {
    group = "verification"
    doLast {
        check(enabledVariants == setOf("demoDebug")) { "Unexpected application variants: $enabledVariants" }
        check(configurations.getByName("implementation").dependencies.none {
            it is ProjectDependency && it.path == ":data:fake"
        }) { "Fake must be wired only by demoImplementation" }
        println("Only demoDebug is enabled; fake is absent from main implementation.")
    }
}
kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:platform"))
    "demoImplementation"(project(":data:fake"))
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.hilt.android)
    implementation(libs.hilt.compose)
    implementation(libs.serialization.json)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.compose.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    debugImplementation(libs.compose.test.manifest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.compose.test)
    // Compose Test's older transitive Espresso uses reflection removed on Android 16.
    androidTestImplementation(libs.espresso.core)
}
