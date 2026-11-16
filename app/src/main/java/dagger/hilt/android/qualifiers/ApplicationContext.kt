// LeXia stand-in for a part of NuvioTV that the copied plugin files refer to but LeXia does not
// have (accounts, sync, profiles, CloudStream extensions, Hilt). Not Nuvio code: keep it minimal.
package dagger.hilt.android.qualifiers

/** Marker only: LeXia passes the application context by hand. */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationContext
