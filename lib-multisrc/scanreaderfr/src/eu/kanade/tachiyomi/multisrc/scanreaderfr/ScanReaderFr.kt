package eu.kanade.tachiyomi.multisrc.scanreaderfr

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia

class ScanReaderFr(
    override val name: String,
    override val baseUrl: String,
) : MangaThemesia(
    name,
    baseUrl,
    "fr",
)
