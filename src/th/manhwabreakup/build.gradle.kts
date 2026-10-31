import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ManhwaBreakup"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"
    theme = "madara"

    source {
        lang = "th"
        baseUrl = "https://www.manhwabreakup.com"
    }
}

dependencies {

    implementation(project(":lib:tilescramble"))
}
