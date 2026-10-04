import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Yidan Girl"
    versionCode = 7
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        name = "一耽女孩"
        lang = "zh"
        baseUrl {
            custom("https://yidan12.club")
        }
    }
}
