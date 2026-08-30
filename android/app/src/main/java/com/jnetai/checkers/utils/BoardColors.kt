package com.jnetai.checkers.utils

import android.graphics.Color

/**
 * Configurable board colours.
 *
 * The default piece colours are RED (player 1) and BLUE (player 2) so every
 * piece is clearly visible on both the light and the dark squares. The dark
 * (playable) square colour defaults to the original brown but can be changed,
 * and a light halo rim is drawn around every piece so it stays visible on any
 * square colour.
 */
object BoardColors {

    /** Colour of the two players' pieces (player 1 was BLACK, player 2 WHITE). */
    enum class PieceColor(val displayName: String, val fill: Int, val edge: Int) {
        RED("Red", Color.rgb(229, 57, 53), Color.rgb(183, 28, 28)),
        BLUE("Blue", Color.rgb(30, 136, 229), Color.rgb(13, 71, 161)),
        GREEN("Green", Color.rgb(67, 160, 71), Color.rgb(46, 125, 50)),
        ORANGE("Orange", Color.rgb(251, 140, 0), Color.rgb(239, 108, 0)),
        PURPLE("Purple", Color.rgb(142, 36, 170), Color.rgb(106, 27, 154)),
        CYAN("Cyan", Color.rgb(0, 172, 193), Color.rgb(0, 131, 143)),
        YELLOW("Yellow", Color.rgb(255, 213, 79), Color.rgb(255, 160, 0)),
        WHITE("White", Color.rgb(245, 240, 230), Color.rgb(191, 179, 160)),
        BLACK("Black", Color.rgb(28, 28, 30), Color.rgb(10, 10, 10))
    }

    /** Colour of the dark (playable) squares; the light squares never change. */
    enum class SquareColor(val displayName: String, val fill: Int, val alt: Int) {
        BROWN("Brown (default)", Color.rgb(181, 136, 99), Color.rgb(165, 113, 78)),
        DARK_GREY("Dark Grey", Color.rgb(109, 109, 109), Color.rgb(90, 90, 90)),
        RED("Red", Color.rgb(198, 40, 40), Color.rgb(183, 28, 28)),
        BLUE("Blue", Color.rgb(21, 101, 192), Color.rgb(13, 71, 161)),
        GREEN("Green", Color.rgb(46, 125, 50), Color.rgb(27, 94, 32)),
        ORANGE("Orange", Color.rgb(239, 108, 0), Color.rgb(230, 81, 0)),
        PURPLE("Purple", Color.rgb(106, 27, 154), Color.rgb(74, 20, 140)),
        CYAN("Cyan", Color.rgb(0, 131, 143), Color.rgb(0, 96, 100))
    }
}