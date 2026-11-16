#!/usr/bin/env python3
"""Puts LeXia inside a checkout of the NuvioTV fork, so one app is built from both.

Nuvio stays what it is (its add-ons, sources, torrents and player are its own code); LeXia goes in
as a library module ":lexia" and becomes the home screen. "Oynat" in LeXia opens Nuvio's page of
the film inside the same app (nuvio-bridge/Media.kt).

    python3 scripts/merge_nuvio.py <path to the NuvioTV checkout>

What changes in the Nuvio checkout (nothing is committed there):
  - lexia/                   LeXia's code and pictures, its own media screens left out
  - settings.gradle.kts      includes :lexia
  - app/build.gradle.kts     LeXia's package name and version number, depends on :lexia,
                             ARM builds only, Nuvio's own updater off (LeXia updates the app)
  - app manifests            Nuvio no longer shows as an app of its own; name, icon: LeXia's
  - NuvioApplication.kt      runs LeXia's start-up work
  - local.properties         the TMDB key

LeXia's resources get the prefix "lx_": a library's picture named like one of the app's would be
silently replaced by the app's.
"""
import os
import pathlib
import re
import shutil
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
NUVIO = pathlib.Path(sys.argv[1]).resolve()
SRC = ROOT / "app/src/main"
OUT = NUVIO / "lexia"
PKG = "tv/lexia/launcher"
ANDROID = "http://schemas.android.com/apk/res/android"

# LeXia's own source list, player and add-on screens: Nuvio does all of that here.
LEFT_OUT = {
    "PlayerActivity.kt", "PlayerSupport.kt", "MediaSourcePickerActivity.kt", "MediaAddonsActivity.kt",
    "MediaAddonRepository.kt", "NuvioJsRepository.kt", "NuvioJsRuntime.kt", "MediaPlaybackStore.kt",
    "StreamTags.kt", "Media.kt",
}
LEFT_OUT_SCREENS = {".PlayerActivity", ".MediaSourcePickerActivity", ".MediaAddonsActivity"}
# Settings of the application as a whole are Nuvio's; these belonged to LeXia's own.
LEFT_OUT_RES = {"xml/backup_rules.xml", "xml/data_extraction_rules.xml", "xml/network_security_config.xml",
                "raw/isrg_root_x1.pem", "raw/isrg_root_x2.pem"}


def fail(message):
    sys.exit("merge_nuvio: " + message)


def replace_once(path, old, new, count=1):
    text = path.read_text(encoding="utf-8")
    if text.count(old) < 1:
        fail(f"{path.relative_to(NUVIO)}: expected text not found: {old[:70]!r}")
    path.write_text(text.replace(old, new, count), encoding="utf-8")


def rename_refs_kotlin(text):
    # R.drawable.ic_tv -> R.drawable.lx_ic_tv; android.R.* stays.
    return re.sub(r"(?<![\w.])R\.(drawable|raw|xml)\.(\w+)", r"R.\1.lx_\2", text)


def rename_refs_xml(text):
    text = re.sub(r"@(drawable|raw|xml)/(\w+)", r"@\1/lx_\2", text)
    text = text.replace("@style/Launcher", "@style/LxLauncher")
    text = text.replace("@string/home_button_", "@string/lx_home_button_")
    return text


def copy_code():
    target = OUT / "src/main/java" / PKG
    target.mkdir(parents=True)
    for f in sorted((SRC / "java" / PKG).glob("*.kt")):
        if f.name in LEFT_OUT:
            continue
        (target / f.name).write_text(rename_refs_kotlin(f.read_text(encoding="utf-8")), encoding="utf-8")
    for f in sorted((ROOT / "nuvio-bridge").glob("*.kt")):
        (target / f.name).write_text(rename_refs_kotlin(f.read_text(encoding="utf-8")), encoding="utf-8")


def copy_resources():
    for f in sorted((SRC / "res").rglob("*")):
        if not f.is_file():
            continue
        folder = f.parent.name
        kind = folder.split("-")[0]
        if f"{kind}/{f.name}" in LEFT_OUT_RES:
            continue
        to = OUT / "src/main/res" / folder
        to.mkdir(parents=True, exist_ok=True)
        if kind == "values":
            text = rename_refs_xml(f.read_text(encoding="utf-8"))
            text = text.replace('name="Launcher"', 'name="LxLauncher"').replace('name="home_button_', 'name="lx_home_button_')
            (to / ("lx_" + f.name)).write_text(text, encoding="utf-8")
        elif f.suffix == ".xml":
            (to / ("lx_" + f.name)).write_text(rename_refs_xml(f.read_text(encoding="utf-8")), encoding="utf-8")
        else:
            shutil.copyfile(f, to / ("lx_" + f.name))
    shutil.copytree(SRC / "assets", OUT / "src/main/assets")


def write_manifest():
    ET.register_namespace("android", ANDROID)
    ET.register_namespace("tools", "http://schemas.android.com/tools")
    tree = ET.parse(SRC / "AndroidManifest.xml")
    manifest = tree.getroot()
    manifest.attrib.pop("package", None)
    name = f"{{{ANDROID}}}name"
    theme = f"{{{ANDROID}}}theme"
    app = manifest.find("application")
    app.attrib.clear()
    for node in list(app):
        if node.attrib.get(name) in LEFT_OUT_SCREENS:
            app.remove(node)
            continue
        if node.attrib.get(name, "").startswith("."):
            node.set(name, "tv.lexia.launcher" + node.attrib[name])
        # The application's theme is Nuvio's now; each LeXia screen names its own.
        if node.tag == "activity" and theme not in node.attrib:
            node.set(theme, "@style/Launcher")
    target = OUT / "src/main/AndroidManifest.xml"
    tree.write(target, encoding="utf-8", xml_declaration=True)
    target.write_text(rename_refs_xml(target.read_text(encoding="utf-8")), encoding="utf-8")


def write_module_build():
    (OUT / "build.gradle.kts").write_text('''// Written by LeXia's scripts/merge_nuvio.py: LeXia as a library inside Nuvio's build.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

fun quoted(value: String) = "\\"" + value.replace("\\\\", "\\\\\\\\").replace("\\"", "\\\\\\"") + "\\""

android {
    namespace = "tv.lexia.launcher"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        buildConfigField("String", "BUILD_NO", quoted(System.getenv("LEXIA_BUILD_NO") ?: System.getenv("GITHUB_RUN_NUMBER") ?: "0"))
        buildConfigField("boolean", "SETUP", "false")
        buildConfigField("String", "SITE", quoted(System.getenv("LEXIA_SITE") ?: "https://lexia.invalid"))
        buildConfigField("String", "TMDB_API_KEY", quoted(System.getenv("TMDB_API_KEY") ?: ""))
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // The trailer player is compiled against Media3's interfaces; the classes that run are the
    // ones Nuvio ships (its own build of Media3 1.8).
    compileOnly("androidx.media3:media3-exoplayer:1.8.0")
    compileOnly("androidx.media3:media3-exoplayer-hls:1.8.0")
    compileOnly("androidx.media3:media3-common:1.8.0")
    compileOnly("androidx.media3:media3-datasource:1.8.0")
    compileOnly("androidx.media3:media3-ui:1.8.0")
}
''', encoding="utf-8")


def patch_nuvio():
    settings = NUVIO / "settings.gradle.kts"
    settings.write_text(settings.read_text(encoding="utf-8").rstrip("\n") + '\ninclude(":lexia")\n', encoding="utf-8")

    build = NUVIO / "app/build.gradle.kts"
    replace_once(build, 'applicationId = "com.nuvio.tv"', 'applicationId = "tv.lexia.launcher"')
    text = build.read_text(encoding="utf-8")
    text, n = re.subn(r"versionCode = \d+", 'versionCode = (System.getenv("LEXIA_VERSION_CODE") ?: System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()', text, count=1)
    if n != 1:
        fail("app/build.gradle.kts: versionCode not found")
    build.write_text(text, encoding="utf-8")
    replace_once(build, "dependencies {\n    coreLibraryDesugaring", 'dependencies {\n    implementation(project(":lexia"))\n    coreLibraryDesugaring')
    replace_once(build, 'include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")', 'include("armeabi-v7a", "arm64-v8a")')
    # The full flavour comes first in the file: its updater would fetch Nuvio's own releases.
    replace_once(build, 'buildConfigField("boolean", "FEATURE_IN_APP_UPDATES_ENABLED", "true")',
                 'buildConfigField("boolean", "FEATURE_IN_APP_UPDATES_ENABLED", "false")')

    for manifest in sorted((NUVIO / "app/src").glob("*/AndroidManifest.xml")):
        text = manifest.read_text(encoding="utf-8")
        # LeXia is the one entry in the TV's app list (and the home screen).
        text = re.sub(r'[ \t]*<category android:name="android\.intent\.category\.(LEANBACK_)?LAUNCHER" />\n', "", text)
        if manifest.parent.name == "main":
            for old, new in (
                ('android:label="@string/app_name"\n        android:largeHeap', 'android:label="LeXia Launcher"\n        android:largeHeap'),
                ('android:banner="@mipmap/banner"\n        android:icon="@mipmap/ic_launcher"',
                 'android:banner="@drawable/lx_banner"\n        android:icon="@drawable/lx_ic_launcher"'),
            ):
                if old not in text:
                    fail(f"app manifest: expected text not found: {old[:50]!r}")
                text = text.replace(old, new, 1)
        manifest.write_text(text, encoding="utf-8")

    application = NUVIO / "app/src/main/java/com/nuvio/tv/NuvioApplication.kt"
    replace_once(application, "        super.onCreate()\n", "        super.onCreate()\n        tv.lexia.launcher.App.start(this)\n")

    props = NUVIO / "local.properties"
    existing = props.read_text(encoding="utf-8") if props.exists() else ""
    props.write_text(existing + f'\nTMDB_API_KEY={os.environ.get("TMDB_API_KEY", "")}\n', encoding="utf-8")


def main():
    if not (NUVIO / "app/build.gradle.kts").exists():
        fail(f"{NUVIO} is not a NuvioTV checkout")
    if OUT.exists():
        shutil.rmtree(OUT)
    copy_code()
    copy_resources()
    write_manifest()
    write_module_build()
    patch_nuvio()
    print(f"LeXia merged into {NUVIO}")


main()
