package net.swarmshine.familty.archive

class DownloadStrategy (){

    private val previousDownloadWasSuccessful = true

    fun downloadImage(){
        when(downloadImageByUrl()){
            is Success -> {

            }
            is TooManyRequests -> {
                if(previousDownloadWasSuccessful){
                    restartHttpClient()
                } else {
                    wait
                }
            }

        }

    }

    open class DownloadResult
    sealed class Success: DownloadResult()
    sealed class TooManyRequests(val timeout: Long): DownloadResult()
    sealed class Error: DownloadResult()

    fun downloadImageByUrl(): DownloadResult {
        TODO()
    }
}