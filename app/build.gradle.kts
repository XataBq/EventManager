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
        versionCode = 23
        versionName = "0.10.3-m03"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "backend"
    productFlavors {
        create("remote") {
            dimension = "backend"
            applicationIdSuffix = ".remote"
            versionNameSuffix = "-remote"
        }
        create("demo") {
            dimension = "backend"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
        }
    }
    buildFeatures { compose = true; buildConfig = true }
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
        check(enabledVariants == setOf("demoDebug", "remoteDebug")) { "Unexpected application variants: $enabledVariants" }
        check(configurations.getByName("implementation").dependencies.none {
            it is ProjectDependency && it.path == ":data:fake"
        }) { "Fake must be wired only by demoImplementation" }
        check(configurations.getByName("remoteDebugRuntimeClasspath").incoming.resolutionResult.allComponents.none {
            it.id.displayName.contains("project :data:fake")
        }) { "Remote runtime must not contain fake" }
        check(file("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.none {
            it.readText().contains("import ru.eventpass.app.feature.tools.") ||
                it.readText().contains("import ru.eventpass.app.feature.gallery.") ||
                it.readText().contains("import ru.eventpass.data.fake.")
        }) { "Main must not import demo tools, gallery or fake" }
        println("demoDebug/remoteDebug enabled; fake absent from main and remote runtime.")
    }
}
kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:platform"))
    "demoImplementation"(project(":data:fake"))
    "testDemoImplementation"(project(":data:fake"))
    "remoteImplementation"(project(":data:remote"))
    "remoteImplementation"(project(":data:local"))
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
    androidTestImplementation(libs.zxing.core)
}
