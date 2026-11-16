// LeXia stand-in: Nuvio's copied StreamBadge carries @Serializable; LeXia has no kotlinx.serialization.
package kotlinx.serialization

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class Serializable
