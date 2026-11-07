package io.github.ccch1mneyyy.dsh_app.pet

import java.io.File

/** Numeric compatibility fixtures shared with Dart; no bundled mascot artwork. */
internal object PetFixture {
    val ids = listOf("test-rectangle-5x", "test-rectangle-1x")
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, "app/test/pets/fixtures"), File(it, "test/pets/fixtures")) }
            .first { File(it, "${ids.first()}.json").isFile }
    }
    fun pack(id: String): PetPack = PetPack.parse(File(root, "$id.json").readText())
    fun bounds(id: String, name: String): IntArray = File(root, "$id.bounds").readLines()
        .first { it.startsWith("$name ") }.split(" ").drop(1).map(String::toInt).toIntArray()
}
