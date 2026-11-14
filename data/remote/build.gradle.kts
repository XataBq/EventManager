plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.serialization) }
android { namespace = "ru.eventpass.data.remote"; compileSdk = 37; defaultConfig { minSdk = 28 }; compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 } }
kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
dependencies { implementation(project(":core:domain")); implementation(libs.okhttp); implementation(libs.serialization.json); implementation(libs.coroutines.core); testImplementation(libs.junit); testImplementation(libs.coroutines.test); testImplementation(libs.mockwebserver) }

tasks.withType<Test>().configureEach {
 val staging = providers.environmentVariable("EVENTPASS_STAGING_SMOKE").getOrElse("")
 environment("EVENTPASS_STAGING_SMOKE", staging)
 inputs.property("stagingSmoke", staging)
}
