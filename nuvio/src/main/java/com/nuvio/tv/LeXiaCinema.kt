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

    /** LeXia's own Turkish story of the film or episode, for the pause screen (Nuvio's may be English). */
    @Volatile
    var description = ""

    /** Which episode [description] is about ("2:5"), "" for a film; Nuvio may move on to the next one. */
    @Volatile
    var descriptionOf = ""

    /** A scene in the credits (TMDB keywords): 1 = during them, 2 = after them, 3 = both; 0 = none or unknown. */
    @Volatile
    var creditsScene = 0

    /** Films to offer while the credits roll ("Bunu sevdiysen"); empty for episodes. */
    @Volatile
    var suggestions: List<Suggestion> = emptyList()

    class Suggestion(val title: String, val poster: String, val line: String)

    /** Quiet: sinema modu is on and something plays. */
    val quiet: Boolean get() = enabled && playing
}
