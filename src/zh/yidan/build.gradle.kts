import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Yidan Girl"
    versionCode = 6
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        name = "一耽女孩"
        lang = "zh"
        baseUrl {
            custom("https://yidan12.club")
        }
    }
}
