// LeXia's own (not Nuvio code): "Sinema modu", shared by LeXia and the player screen it opens.
package com.nuvio.tv

/**
 * Sinema modu (Ayarlar › Oynatma ve Takip): a film starts behind a curtain that opens, and while
 * anything plays LeXia keeps quiet (no notices, no update installing itself mid-film).
 */
object LeXiaCinema {
    /** The setting; LeXia writes it before a player opens. */
    @Volatile
    var enabled = true

    /** A film or an episode is on screen right now (either player). */
    @Volatile
    var playing = false

    /** The film's own colour (ARGB, 0 = none): the player's bar and highlights take it. */
    @Volatile
    var accent = 0

    /** Light mode (weaker TVs): the player leaves out the heavier touches. */
    @Volatile
    var light = false

    /** Quiet: sinema modu is on and something plays. */
    val quiet: Boolean get() = enabled && playing
}
