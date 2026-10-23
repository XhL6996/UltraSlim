package app.ultraslim.ui.history

/** Main-thread cache. A relink invalidates both cached results and older in-flight resolutions. */
internal class OriginalUriCache {
    data class Source(val id: Long, val uri: String)

    private val current = mutableMapOf<Long, String>()
    private val resolved = mutableMapOf<Long, String>()
    private val inFlight = mutableSetOf<Source>()

    fun plan(sources: List<Source>): List<Source> = sources.distinct().filter { source ->
        if (current.put(source.id, source.uri) != source.uri) resolved.remove(source.id)
        source.id !in resolved && inFlight.add(source)
    }

    fun complete(sources: List<Source>, results: Map<Source, String>) {
        inFlight.removeAll(sources.toSet())
        results.forEach { (source, uri) ->
            if (current[source.id] == source.uri) resolved[source.id] = uri
        }
    }

    fun abandon(sources: List<Source>) { inFlight.removeAll(sources.toSet()) }
    fun removeUris(uris: Set<String>) { resolved.entries.removeAll { it.value in uris } }
    fun snapshot(): Map<Long, String> = resolved.toMap()
}
