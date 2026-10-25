package com.opensource.routesim.ui

import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource

object AppTileSources {
    const val OSM_GERMANY = "osm_germany"
    const val OSM_OFFICIAL = "osm_official"

    val germanyMirror: ITileSource = XYTileSource(
        "OSM Germany",
        0,
        19,
        256,
        ".png",
        arrayOf("https://tile.openstreetmap.de/"),
        "© OpenStreetMap contributors",
        TileSourcePolicy(
            2,
            TileSourcePolicy.FLAG_NO_BULK or
                TileSourcePolicy.FLAG_NO_PREVENTIVE or
                TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL or
                TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED,
        ),
    )

    val official: ITileSource = TileSourceFactory.MAPNIK

    fun resolve(id: String?): ITileSource =
        if (id == OSM_OFFICIAL) official else germanyMirror
}
