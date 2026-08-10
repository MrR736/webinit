# WebInit

WebInit is a native Android library for serving embedded web content from a local HTTP server and displaying it through Android `WebView`.

The library packages web content inside a `.pak` file, extracts it to the application's cache directory, starts a local HTTP server, and serves the files through:

```text
http://127.0.0.1:8080/
```

## Features

* Native C++ HTTP server
* Android JNI interface
* Embedded `.pak` web resources
* Optional persistent cache
* `arm64-v8a` support
* `armeabi-v7a` support
* `x86` support
* `x86_64` support
* C++17
* Android API 24+
* No external HTTP server required

## Requirements

* Android API 24+
* Android SDK 36
* NDK `27.0.12077973`
* C++17
* Gradle 8+
* AndroidX

## Installation

### JitPack

Add JitPack to your application's repositories.

For newer Gradle projects, add it to `settings.gradle`:

```gradle
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then add WebInit:

```gradle
dependencies {
    implementation "com.github.mrr736:webinit:1.0.0"
}
```

Replace `1.0.0` with the desired Git tag or release.

## Build from Source

Clone the repository:

```bash
git clone https://github.com/mrr736/webinit.git
cd webinit
```

Build the library:

```bash
./gradlew :lib:assembleRelease
```

The generated AAR will be located in:

```text
lib/build/outputs/aar/
```

For example:

```text
com.mrr736.webinit-1.0.0-release.aar
```

## Local AAR

If you do not want to use JitPack, copy the generated AAR into your application's `libs` directory:

```text
app/
└── libs/
    └── com.mrr736.webinit-1.0.0-release.aar
```

Then add:

```gradle
dependencies {
    implementation files("libs/com.mrr736.webinit-1.0.0-release.aar")
}
```

## Web Content

WebInit expects a PAK file containing the web application.

For example:

```text
webinit.pak
└── index.html
```

The PAK can contain additional files:

```text
webinit.pak
├── index.html
├── css/
│   └── style.css
├── js/
│   └── app.js
├── assets/
│   └── image.png
└── ...
```

The PAK is placed in the Android application's assets:

```text
app/src/main/assets/webinit.pak
```

## Cache Mode

WebInit provides a cache-preserving startup mode.

The first startup extracts the PAK:

```text
/data/user/0/<package>/cache/webinit/
```

Subsequent startups reuse the extracted files instead of extracting the PAK again.

This is useful for large web applications because the PAK does not need to be decompressed every time the application starts.

## HTTP Server

The native server listens on:

```text
127.0.0.1:8080
```

The web application is available at:

```text
http://127.0.0.1:8080/index.html
```

The server only listens on the loopback interface by default, so the web content is not exposed to the local network.

## Using

The simplest way to use WebInit is to extend `WebInit` from your `Activity`.

```java
package com.mrr736.webinit.test;

import android.os.Bundle;
import com.mrr736.webinit.WebInit;

public class MainActivity extends WebInit {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Init("webinit.pak", 8080, "127.0.0.1");
        /* WebInit performs the actual initialization. */
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
```

### Parameters

```java
Init("webinit.pak", 8080, "127.0.0.1");
```

The parameters are:

| Parameter     | Type     | Description                                              |
| ------------- | -------- | -------------------------------------------------------- |
| `webinit.pak` | `String` | PAK file located in the application's `assets` directory |
| `8080`        | `int`    | Local HTTP server port                                   |
| `127.0.0.1`   | `String` | Local server host address                                |

Place the PAK file in:

```text
app/src/main/assets/webinit.pak
```

For example:

```text
app/
└── src/
    └── main/
        ├── assets/
        │   └── webinit.pak
        └── java/
            └── com/
                └── mrr736/
                    └── webinit/
                        └── test/
                            └── MainActivity.java
```

After initialization, the web application is served from:

```text
http://127.0.0.1:8080/index.html
```

The `WebInit` base class handles the native WebInit initialization, PAK extraction/cache, HTTP server, and WebView setup.

## PAK Format

WebInit uses a ZIP-compatible PAK format.

The native implementation uses:

* [libdeflate](https://github.com/ebiggers/libdeflate)
* [miniz](https://github.com/richgel999/miniz)

DEFLATE-compressed files are decompressed using `libdeflate`.

Stored files (`STORE`) are copied directly.

## Supported ABIs

The library currently builds for:

```text
arm64-v8a
armeabi-v7a
x86
x86_64
```

## Android Configuration

The library uses:

```gradle
android {
    namespace "com.mrr736.webinit"

    compileSdk 36

    ndkVersion "27.0.12077973"

    defaultConfig {
        minSdk 24
        targetSdk 36

        versionCode 100
        versionName "1.0.0"

        externalNativeBuild {
            cmake {
                cppFlags "-std=c++17"
            }
        }

        ndk {
            abiFilters "arm64-v8a", "armeabi-v7a", "x86", "x86_64"
        }
    }
}
```

## Versioning

WebInit follows semantic versioning:

```text
MAJOR.MINOR.PATCH
```

For example:

```text
1.0.0
1.0.1
1.1.0
2.0.0
```

Create a release tag:

```bash
git tag 1.0.0
git push origin 1.0.0
```

The tag can then be used by JitPack:

```gradle
implementation "com.github.mrr736:webinit:1.0.0"
```

## Acknowledgements

- miniz
- libdeflate
- ghc::filesystem

