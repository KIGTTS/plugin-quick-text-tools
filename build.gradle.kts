plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "com.kigtts.tools.quicktext"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { jvmToolchain(17) }
}
tasks.register<Sync>("collectPluginDependencies") { from(configurations.getByName("debugRuntimeClasspath"));into(layout.buildDirectory.dir("plugin-dependencies")) }
dependencies { compileOnly(project(":plugin-android-api")) }
