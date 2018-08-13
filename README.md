# jpegimagestomovie
Convert collection of JPEG images to Quicktime video

Example usage:

```
import com.sun.samples.JpegImagesToMovie

var savedBuffer = List.empty[Array[Byte]]
val oml: MediaLocator = JpegImagesToMovie.createMediaLocator(moviePath)
val width: Int = 640
val height: Int = 480
val frameRate: Int = 15
...
imagesToMovie.doIt(width, height, frameRate, savedBuffer.asJava, oml)
 
```
