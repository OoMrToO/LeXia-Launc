// LeXia stand-in for NuvioApplication.newImageLoader (LeXia is the application).
// Nuvio's image loader decodes SVG and GIF; its player buttons are SVG files.
package com.nuvio.tv

import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.svg.SvgDecoder

object LeXiaImages {
    /** Called once from the application's onCreate, before any Nuvio screen asks for an image. */
    fun install(context: Context) {
        val app = context.applicationContext
        SingletonImageLoader.setSafe {
            ImageLoader.Builder(app)
                .components {
                    if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
                    add(SvgDecoder.Factory())
                }
                .build()
        }
    }
}
