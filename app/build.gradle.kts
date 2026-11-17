import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val apiBase = providers.gradleProperty("eventpass.apiBaseUrl").getOrElse("https://staging.eventpass.dikanskii.ru/api/")
val apiUri = URI(apiBase)
require(apiUri.scheme == "https" && !apiUri.host.isNullOrBlank() && apiUri.userInfo == null && apiUri.query == null && apiUri.fragment == null && apiUri.path.endsWith("/")) {
    "eventpass.apiBaseUrl must be an HTTPS base directory without credentials, query or fragment"
}

android {
    namespace = "ru.eventpass.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "ru.eventpass.android"
        minSdk = 28
        targetSdk = 37
        versionCode = 25
        versionName = "0.10.9-m09-rc2"
        buildConfigField("String", "API_BASE_URL", "\"$apiBase\"")
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
    buildTypes.named("release") {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        // Intentionally unsigned: production credentials are not part of the repository.
    }
}

// Acceptance/debug plus unsigned remote release; demo is never a release product.
androidComponents {
    beforeVariants { variant -> variant.enable = variant.buildType == "debug" || variant.name == "remoteRelease" }
}
val enabledVariants = mutableSetOf<String>()
androidComponents.onVariants { enabledVariants += it.name }
tasks.register("verifyDemoIsolation") {
    group = "verification"
    doLast {
        check(enabledVariants == setOf("demoDebug", "remoteDebug", "remoteRelease")) { "Unexpected application variants: $enabledVariants" }
        check(configurations.getByName("implementation").dependencies.none {
            it is ProjectDependency && it.path == ":data:fake"
        }) { "Fake must be wired only by demoImplementation" }
        for (variant in listOf("remoteDebug", "remoteRelease")) check(configurations.getByName("${variant}RuntimeClasspath").incoming.resolutionResult.allComponents.none {
            it.id.displayName.contains("project :data:fake")
        }) { "Remote runtime must not contain fake" }
        check(file("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.none {
            it.readText().contains("import ru.eventpass.app.feature.tools.") ||
                it.readText().contains("import ru.eventpass.app.feature.gallery.") ||
                it.readText().contains("import ru.eventpass.data.fake.")
        }) { "Main must not import demo tools, gallery or fake" }
        println("demoDebug/remoteDebug/unsigned remoteRelease enabled; fake absent from main and both remote runtimes.")
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
    "androidTestRemoteImplementation"(libs.okhttp)
}
