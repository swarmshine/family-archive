package net.swarmshine.familty.archive

import java.nio.file.Path

object FileNameMatcher {
    fun extractFileNumber(file: Path):Int?{

    }

    fun fileName(page: Int, fileExtension: String): String{
        return "image-$page.$fileExtension"
    }
}