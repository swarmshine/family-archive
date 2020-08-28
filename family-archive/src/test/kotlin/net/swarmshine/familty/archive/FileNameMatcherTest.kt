package net.swarmshine.familty.archive

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Paths

internal class FileNameMatcherTest{
    @Test
    fun `extract number from correct file name`() {
        FileNameMatcher.extractFileNumber(Paths.get("foo/bar/image-234.jpg")).shouldBe(234)
        FileNameMatcher.extractFileNumber(Paths.get("image-234.jpg")).shouldBe(234)
    }

    @Test
    fun `extract number for incorrect file name returns null`() {
        FileNameMatcher.extractFileNumber(Paths.get("foo/bar/fsjfd-fsje.jpg")).shouldBeNull()
        FileNameMatcher.extractFileNumber(Paths.get("fsjfd-fsje.jpg")).shouldBeNull()
    }
}