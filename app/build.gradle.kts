plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val mobileOpenCl = providers.gradleProperty("mobileOpenCl").orNull == "true"
val mobileGpu = providers.gradleProperty("mobileGpu").orNull == "true"
val mobileFast = providers.gradleProperty("mobileFast").orNull == "true"
android {
    namespace = "com.voxstudio.app"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "com.voxstudio.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 16
        versionName = "0.1.15"
        buildConfigField("boolean", "MOBILE_FAST", mobileFast.toString())
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { cppFlags += listOf("-std=c++17", "-O3"); arguments += "-DVOX_MOBILE_OPENCL=${if (mobileOpenCl) "ON" else "OFF"}"; arguments += "-DVOX_MOBILE_GPU=${if (mobileGpu) "ON" else "OFF"}"; arguments += "-DVOX_MOBILE_FAST=${if (mobileFast) "ON" else "OFF"}" } }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("main").jniLibs.setSrcDirs(if(mobileOpenCl || mobileGpu) listOf("../../../work/cpu-jni") else emptyList<String>())
    buildFeatures { buildConfig = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
    packaging { jniLibs { useLegacyPackaging = false; excludes += "**/libOpenCL.so" } }
    lint { abortOnError = true }
}
dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
