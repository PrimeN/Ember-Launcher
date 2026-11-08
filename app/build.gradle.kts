import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.security.MessageDigest
import java.util.Properties
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/** The two engines use different FFmpeg ABIs. Rename libmpv's private ELF dependencies
 * and its JNI loader strings without changing symbols, byte offsets or JNI class names.
 * Never use packaging pickFirst for these libraries: that would break one engine.
 */
@org.gradle.api.tasks.CacheableTask
abstract class IsolateMpvNative : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val upstream: org.gradle.api.file.RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val filters: org.gradle.api.file.DirectoryProperty
    @get:OutputFile abstract val output: org.gradle.api.file.RegularFileProperty
    private val names = linkedMapOf(
        "libavcodec.so" to "libemcodec.so", "libavutil.so" to "libemutil.so",
        "libavformat.so" to "libemformat.so", "libavfilter.so" to "libemfilter.so",
        "libavdevice.so" to "libemdevice.so", "libswresample.so" to "libemresample.so",
        "libswscale.so" to "libemscale.so", "libmpv.so" to "libemv.so", "libplayer.so" to "libemplay.so",
    )
    private fun renameNative(bytes: ByteArray, loader:Boolean): ByteArray {
        if(loader) {
            // Upstream's verbose JNI logger includes arbitrary URLs/headers in mpv messages.
            // Keep the prefix/level but suppress the message text (same-size format literal).
            val original="[%s:%s] %s\u0000".toByteArray();val redacted="[%s:%s] **\u0000".toByteArray()
            var changed=0
            for(i in 0..bytes.size-original.size)if(original.indices.all {bytes[i+it]==original[it]}) {redacted.copyInto(bytes,i);changed++}
            require(changed==1) {"Native log format changed; review privacy patch."}
        }
        names.forEach { (old,new) ->
            val a=(old+'\u0000').toByteArray(Charsets.US_ASCII);val b=(new+'\u0000').toByteArray(Charsets.US_ASCII)
            require(a.size==b.size)
            for(i in 0..bytes.size-a.size) if(a.indices.all { bytes[i+it]==a[it] }) b.copyInto(bytes,i)
        }
        return bytes
    }
    private fun loaderClass(bytes:ByteArray):ByteArray {
        val view=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(view.int==0xCAFEBABE.toInt());view.position(8);val count=view.short.toInt() and 65535
        var slot=1;var changed=0
        while(slot<count) {
            when(val tag=view.get().toInt() and 255) {
                1 -> {val length=view.short.toInt() and 65535;val position=view.position();val data=ByteArray(length);view.get(data)
                    val replacement=when(String(data,Charsets.UTF_8)){"mpv"->"emv";"player"->"emplay";else->null}
                    if(replacement!=null) {require(replacement.length==length);replacement.toByteArray().copyInto(bytes,position);changed++}}
                3,4,9,10,11,12,17,18 -> view.position(view.position()+4)
                5,6 -> {view.position(view.position()+8);slot++}
                7,8,16,19,20 -> view.position(view.position()+2)
                15 -> view.position(view.position()+3)
                else -> error("Unknown class constant tag $tag")
            };slot++
        }
        require(changed==2) { "Upstream native loader changed; review isolation before upgrading." }
        return bytes
    }
    private fun classes(bytes:ByteArray):ByteArray {
        val out=ByteArrayOutputStream()
        ZipInputStream(bytes.inputStream()).use { input -> ZipOutputStream(out).use { zip ->
            while(true) {val entry=input.nextEntry?:break;val data=input.readBytes()
                zip.putNextEntry(ZipEntry(entry.name).apply { time=0L })
                zip.write(if(entry.name=="dev/jdtech/mpv/MPVLib.class")loaderClass(data) else data);zip.closeEntry()
            }
        } };return out.toByteArray()
    }
    @TaskAction fun isolate() {
        val original=upstream.get().asFile
        val digest=MessageDigest.getInstance("SHA-256").digest(original.readBytes()).joinToString(""){"%02x".format(it)}
        require(digest=="df146592480fc8418415a06b1f1a1d6318b0088e21f52254b0e9a82b61ca8fa2") { "Unexpected libmpv artifact; review dependency before building." }
        val filterRoot=filters.get().asFile
        val hashes=Properties().apply {filterRoot.resolve("checksums.properties").inputStream().use {load(it)}}
        val replaced=mutableSetOf<String>()
        val target=output.get().asFile;target.parentFile.mkdirs()
        ZipFile(original).use { source -> ZipOutputStream(target.outputStream()).use { zip ->
            source.entries().asSequence().forEach { entry ->
                val data=if(entry.name.startsWith("jni/") && entry.name.endsWith("/libavfilter.so")) {
                    val abi=entry.name.split('/')[1]
                    val replacement=filterRoot.resolve("$abi/libavfilter.so").readBytes()
                    val sha=MessageDigest.getInstance("SHA-256").digest(replacement).joinToString(""){"%02x".format(it)}
                    require(sha==hashes.getProperty("$abi.sha256")) {"Native filter checksum mismatch for $abi"}
                    replaced+=abi
                    replacement
                } else source.getInputStream(entry).use { it.readBytes() }
                val name=if(entry.name.startsWith("jni/"))entry.name.substringBeforeLast('/')+'/'+(names[entry.name.substringAfterLast('/')]?:entry.name.substringAfterLast('/')) else entry.name
                zip.putNextEntry(ZipEntry(name).apply { time=0L })
                zip.write(when {name=="classes.jar"->classes(data);name.startsWith("jni/")&&name.endsWith(".so")->renameNative(data,name.endsWith("/libemplay.so"));else->data});zip.closeEntry()
            }
        } }
        require(replaced==setOf("arm64-v8a","armeabi-v7a","x86","x86_64")) {"Missing native filter ABI: $replaced"}
    }
}
val mpvUpstream by configurations.creating { isCanBeConsumed=false;isTransitive=false }
val isolateMpvNative by tasks.registering(IsolateMpvNative::class) {
    upstream.set(layout.file(provider { mpvUpstream.singleFile }))
    filters.set(layout.projectDirectory.dir("native/ffmpeg-filters"))
    output.set(layout.buildDirectory.file("isolated-mpv/libmpv-1.0.0.aar"))
}

// MPEG-H is a standalone, statically linked decoder; APK support remains ARM-only.
val verifyMpeghNative by tasks.registering {
    inputs.dir(layout.projectDirectory.dir("native/mpegh"))
    inputs.files(fileTree("src/main/jniLibs") { include("**/libmpeghJNI.so") })
    doLast {
        val hashes = Properties().apply { file("native/mpegh/checksums.properties").inputStream().use { load(it) } }
        val expected = mapOf("arm64-v8a" to 183, "armeabi-v7a" to 40)
        val files = fileTree("src/main/jniLibs") { include("**/libmpeghJNI.so") }.files
        require(files.map { it.parentFile.name }.toSet() == expected.keys) { "MPEG-H must contain exactly the supported ARM ABIs" }
        for (library in files) {
            val abi = library.parentFile.name
            val bytes = library.readBytes()
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(sha == hashes.getProperty("$abi.sha256")) { "MPEG-H checksum mismatch: $abi" }
            require(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(18).toInt() == expected[abi]) { "MPEG-H CPU mismatch: $abi" }
        }
        val source = file("native/mpegh/source/mpeghdec-r3.0.2.tar.gz").readBytes()
        require(MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) } == hashes.getProperty("source.sha256")) { "MPEG-H source checksum mismatch" }
    }
}
tasks.named("preBuild") { dependsOn(verifyMpeghNative) }

// API keys live in local.properties (not committed) or in environment variables on CI.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun key(name: String): String = System.getenv(name)?.takeIf { it.isNotBlank() }
    ?: localProps.getProperty(name).orEmpty()

// Escape values as Java string literals without logging build credentials.
fun javaString(value: String): String = "\"" + value
    .replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\""

/**
 * Your private set (API keys, addons, IPTV) from EMBER_PRIVATE_BUNDLE, encrypted with
 * EMBER_PRIVATE_PASSWORD so the APK only carries ciphertext. The TV asks for the password
 * (About: tap "0.1 First light" ten times). Format: 1 byte version, 16 bytes salt, 12 bytes IV,
 * AES-256-GCM of the JSON, key from PBKDF2-HMAC-SHA256 (210,000 rounds). Empty without both secrets.
 */
fun privateBundle(): String {
    val plain = key("EMBER_PRIVATE_BUNDLE").trim()
    val pass = key("EMBER_PRIVATE_PASSWORD")
    if (plain.isEmpty() || pass.isEmpty()) return ""
    val rnd = SecureRandom()
    val salt = ByteArray(16).also { rnd.nextBytes(it) }
    val iv = ByteArray(12).also { rnd.nextBytes(it) }
    val spec = PBEKeySpec(pass.toCharArray(), salt, 210_000, 256)
    val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv))
    val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
    return Base64.getEncoder().encodeToString(byteArrayOf(1) + salt + iv + ct)
}

android {
    namespace = "app.ember.tv"
    // API 37: needed by androidx.core 1.19, lifecycle 2.11 and Compose 1.12 (with AGP 9.1 or later).
    compileSdk = 37

    defaultConfig {
        applicationId = "app.ember.tv"
        // Android TV 10 and newer (Google TV Streamer, onn boxes, current TVs and projectors).
        minSdk = 29
        targetSdk = 35
        // Seconds since 2020 keep builds ordered across repositories whose run counters differ.
        // CI pins the value for all Gradle invocations in the same build.
        val buildVersionCode = System.getenv("EMBER_VERSION_CODE")?.toInt()
            ?: Math.toIntExact(Instant.now().epochSecond - 1577836800L)
        require(buildVersionCode in 1..2100000000) { "EMBER_VERSION_CODE is outside Android's supported range" }
        versionCode = buildVersionCode
        versionName = "0.9.8"
        // ARM only: 64-bit, plus 32-bit for TV boxes that run a 32-bit Android on a 64-bit chip
        // (common on streaming boxes). x86 and x86_64 (emulators on PCs) are left out.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }

        for (name in listOf(
            "TRAKT_CLIENT_ID", "TRAKT_CLIENT_SECRET",
            "WETRAKR_CLIENT_ID", "WETRAKR_CLIENT_SECRET",
            "SIMKL_CLIENT_ID", "MAL_CLIENT_ID", "TMDB_API_KEY", "WATCHMODE_API_KEY", "FANART_API_KEY",
            "STREAMING_AVAILABILITY_API_KEY", "MISTRAL_API_KEY",
        )) {
            buildConfigField("String", name, javaString(key(name)))
        }
        buildConfigField("String", "PRIVATE_BUNDLE", "\"${privateBundle()}\"")
        // Every update channel uses public releases, regardless of where the APK is compiled.
        buildConfigField("String", "UPDATE_REPO", "\"PrimeN/Ember-Launcher\"")
    }

    // Optional release signing, set up by the GitHub workflow from repository secrets.
    // Without it, build the debug APK instead.
    val releaseKeystore = System.getenv("EMBER_KEYSTORE_FILE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("EMBER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("EMBER_KEY_ALIAS")
                keyPassword = System.getenv("EMBER_KEY_PASSWORD")
            }
        }
    }

    // Test addons: manifest links from TEST_ADDONS (local.properties or a repository secret),
    // separated by commas, spaces or new lines. Only debug builds get them; release builds never do,
    // so personal addon links cannot ship by accident.
    val testAddons = key("TEST_ADDONS").split(Regex("[\\s,]+")).filter { it.startsWith("http") }.joinToString(",")

    buildTypes {
        debug {
            buildConfigField("String", "TEST_ADDONS", "\"$testAddons\"")
        }
        release {
            // R8: smaller and faster code. Rules for libraries that need them are in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "TEST_ADDONS", "\"\"")
        }
        if (providers.gradleProperty("includePerformanceTests").orNull == "true") {
            create("benchmark") {
                initWith(getByName("release"))
                // A separate, release-optimized test installation leaves the user's launcher,
                // accounts and preferences intact. It never ships in ordinary builds.
                applicationIdSuffix = ".benchmark"
                signingConfig = signingConfigs.getByName("debug")
                matchingFallbacks += listOf("release")
                if (providers.gradleProperty("generateStartupProfiles").orNull == "true") {
                    // Collect original class/method names. R8 rewrites these rules when the
                    // measured profiles are later packaged into the real release.
                    isMinifyEnabled = false
                    isShrinkResources = false
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // Reported in the GitHub run summary; does not stop the build.
        abortOnError = false
        checkReleaseBuilds = false
        xmlReport = true
        htmlReport = true
        // Translations are added gradually.
        disable += setOf("MissingTranslation")
        lintConfig = file("lint.xml")
    }
    testOptions {
        // Android classes in plain JVM tests answer with defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
        // UI regression tests run on the JVM with the app's real resources and manifest.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    mpvUpstream("dev.jdtech.mpv:libmpv:1.0.0@aar")
    implementation(files(isolateMpvNative.flatMap { it.output }))
    implementation("androidx.core:core-ktx:1.19.1")
    // Installs the startup profiles shipped by Compose and the other libraries (plus Ember's own,
    // src/main/baseline-prof.txt), so code used at start is compiled ahead of time even when the
    // APK is side-loaded rather than installed from Google Play.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // Background refresh of Home rows and the IPTV guide (Settings > Features).
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.tv:tv-material:1.1.0")
    // Google's Material icons for the Settings pages (only the icons used stay in release builds; R8 drops the rest).
    // Compose 1.12's BOM no longer lists the icon libraries, so the last release is named here.
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    implementation("androidx.security:security-crypto:1.1.0")

    // Built-in Media3 playback engine
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.11.1")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    // FFmpeg audio decoders from Nextlib (TrueHD, DTS/DTS-HD core, AC3, E-AC3, FLAC, ALAC, MP3, AAC,
    // Opus, Vorbis), plus software video recovery through Nextlib DecoderManager.
    // Version is <Media3 version>-<Nextlib version> and must match Media3 above. (Jellyfin's build,
    // used before, stopped at Media3 1.9.)
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.11.1-0.17.0")
    // Experimental: finds YouTube trailer streams so they play in the built-in player (Settings > Player).
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    // QR code for Settings > Phone setup.
    implementation("com.google.zxing:core:3.5.4")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil:2.7.0") // ImageView loading for the screensaver
    implementation("io.coil-kt:coil-gif:2.7.0") // animated collection covers
    implementation("io.coil-kt:coil-svg:2.7.0") // addon logos (many manifests link an SVG)
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Authenticated network file access; passwords stay in the existing Keystore-backed store.
    implementation("com.hierynomus:smbj:0.15.0")
    implementation("com.github.mwiede:jsch:2.28.8")
    implementation("commons-net:commons-net:3.13.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
