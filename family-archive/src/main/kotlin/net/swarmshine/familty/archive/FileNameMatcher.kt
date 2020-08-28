package net.swarmshine.familty.archive

import java.nio.file.Path

object FileNameMatcher {
    val regex = "\\d+".toRegex()
    fun extractFileNumber(file: Path):Int?{
        return regex.find(file.toString())?.value?.toInt()
    }

    fun fileName(page: Int, fileExtension: String): String{
        return "image-$page.$fileExtension"
    }
}