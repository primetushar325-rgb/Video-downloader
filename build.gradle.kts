// VIDX — top-level build configuration.
// Toolchain versions are pinned here so every build (CI, local, user) is reproducible.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}
