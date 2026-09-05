plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.android")
}

android {
	namespace = "com.bobbypfreely.lpbf"
	compileSdk = 34

	defaultConfig {
		applicationId = "com.bobbypfreely.lpbf"
		minSdk = 26
		targetSdk = 34
		versionCode = 1
		versionName = "0.1.0"
	}

	// Committed debug keystore (app/lpbf-debug.keystore) so every CI run signs debug
	// builds with the SAME certificate. Without this, AGP auto-generates a fresh
	// ~/.android/debug.keystore on each fresh GitHub Actions runner, so every build
	// had a different signer and Android refused to install over the previous APK.
	signingConfigs {
		create("debugStable") {
			storeFile = file("lpbf-debug.keystore")
			storePassword = "lpbfdebug"
			keyAlias = "lpbf-debug"
			keyPassword = "lpbfdebug"
		}
	}

	buildTypes {
		debug {
			signingConfig = signingConfigs.getByName("debugStable")
		}
		release {
			isMinifyEnabled = false
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	kotlinOptions {
		jvmTarget = "17"
	}

	buildFeatures {
		// Needed by the ported MIDI stack's Log.kt, which checks BuildConfig.DEBUG
		buildConfig = true
	}
}

dependencies {
	implementation("androidx.core:core-ktx:1.13.1")
	implementation("androidx.appcompat:appcompat:1.7.0")
	implementation("com.google.android.material:material:1.12.0")
	implementation("androidx.viewpager2:viewpager2:1.1.0")
	implementation("androidx.fragment:fragment-ktx:1.8.2")
	implementation("androidx.activity:activity-ktx:1.9.1")
	implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")

	// Required by the ported MIDI stack (MidiConnection.kt uses CoroutineScope/channels for USB I/O)
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

	// Replaces our hand-rolled MediaCodec/AudioTrack playback code with Google's
	// battle-tested media pipeline, for the Mark and Cut screen's play/pause/seek.
	implementation("androidx.media3:media3-exoplayer:1.4.1")
	implementation("androidx.media3:media3-common:1.4.1")

	// DocumentFile: lets us walk a user-picked folder tree (loose, unzipped Unipack --
	// info/keySound/sounds/keyLed as plain files/folders) the same safe way we already
	// read single files through SAF, since content:// trees aren't plain java.io.File.
	implementation("androidx.documentfile:documentfile:1.0.1")
}
